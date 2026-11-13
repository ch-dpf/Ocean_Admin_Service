package org.ocean.admin.gis.service;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;

import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** 预览只持有短期读取会话，不改变接入、工作编辑及发布状态。 */
@Service
@RequiredArgsConstructor
public class FilePreviewService {
    private static final long TTL_MILLIS = 15 * 60 * 1000;
    private final FileGroupService groups;
    private final FileFormatService formats;
    private final GdalDatasetReader reader;
    private final VectorResourceService vectors;
    private final ObjectMapper json;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Semaphore rendering = new Semaphore(2);
    private final ThreadPoolExecutor preparation =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    runnable -> {
                        Thread thread = new Thread(runnable, "gis-preview");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public record Request(String layerKey, Integer band) {}

    public record Image(byte[] bytes, String mediaType) {}

    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final Long owner;
        final GisFileGroup group;
        final long expires = System.currentTimeMillis() + TTL_MILLIS;
        final ReentrantLock lock = new ReentrantLock();
        volatile String status = "PREPARING";
        volatile boolean closed;
        String error;
        JsonNode descriptor;
        String fileName;
        GdalDatasetReader.ReadContext context;

        Session(Long owner, GisFileGroup group) {
            this.owner = owner;
            this.group = group;
        }
    }

    /** 缺少空间定位的栅格仍能建立图像会话，实际地图能力由驱动描述决定。 */
    public synchronized JsonNode create(Long groupId, Long owner) {
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        cleanup();
        GisFileGroup group = groups.getRequired(groupId);
        if (group.getDuplicateOfGroupId() != null) {
            GisFileGroup effective = groups.getRequired(group.getDuplicateOfGroupId());
            if (!Objects.equals(group.getMatchedRevision(), effective.getInputRevision()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "重复记录所对应的来源已修订，请直接打开规范文件组");
            group = effective;
        }
        if (sessions.size() >= 32
                || sessions.values().stream()
                                .filter(s -> owner.equals(s.owner) && !s.closed)
                                .count()
                        >= 4)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "预览会话已达上限，请关闭其他预览后重试");
        if (group.getEntryFileId() == null) throw new IllegalArgumentException("文件组尚未识别主入口");
        Session session = new Session(owner, group);
        sessions.put(session.id, session);
        try {
            preparation.execute(() -> prepare(session));
        } catch (RejectedExecutionException ex) {
            sessions.remove(session.id);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "预览准备队列已满，请稍后重试");
        }
        return json.createObjectNode()
                .put("id", session.id)
                .put("status", "PREPARING")
                .put("expiresAt", Instant.ofEpochMilli(session.expires).toString());
    }

    private void prepare(Session session) {
        session.lock.lock();
        try {
            if (session.closed) return;
            GisFileFormat format = GisFileFormat.valueOf(session.group.getFormat());
            var members = groups.members(session.group.getId());
            session.fileName =
                    members.stream()
                            .filter(item -> item.getId().equals(session.group.getEntryFileId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("主文件不存在"))
                            .getOriginalName();
            if (format.isVector()) {
                var layers = vectors.sourceLayers(session.group.getEntryFileId());
                if (layers.isEmpty()) throw new IllegalArgumentException("尚未生成原始集合快照，请先在原始文件页生成");
                JsonNode descriptions = vectors.describePreview(session.group.getEntryFileId());
                ObjectNode descriptor = json.createObjectNode().put("kind", "VECTOR");
                var items = descriptor.putArray("layers");
                for (var layer : layers) {
                    ObjectNode item = items.addObject();
                    JsonNode capabilities = descriptions.path(layer.getTableName());
                    if (capabilities.isObject()) item.setAll((ObjectNode) capabilities);
                    item.put("key", layer.getId().toString())
                            .put("name", layer.getLayerName())
                            .put("resourceId", layer.getResourceId().toString())
                            .put(
                                    "total",
                                    layer.getFeatureCount() == null
                                            ? null
                                            : layer.getFeatureCount().toString());
                    JsonNode fields = json.readTree(layer.getSchemaJson()).path("fields");
                    var names = item.putArray("fields");
                    if (fields.isObject()) fields.propertyNames().forEach(names::add);
                    else for (JsonNode field : fields) names.add(field.path("name").asText());
                }
                session.descriptor = descriptor;
            } else {
                String unavailable = formats.availabilityError(format);
                if (unavailable != null) throw new IllegalArgumentException(unavailable);
                if (Set.of("INCOMPLETE", "UNKNOWN").contains(session.group.getAssemblyStatus()))
                    throw new IllegalArgumentException("文件组缺件或尚未识别，不能准备预览");
                session.context = reader.preparePreview(session.group, members);
                ObjectNode descriptor = (ObjectNode) session.context.packageResult().deepCopy();
                descriptor.put("kind", "RASTER");
                session.descriptor = descriptor;
            }
            verify(session);
            session.status = "READY";
        } catch (Exception ex) {
            session.error =
                    ex instanceof ResponseStatusException status
                            ? status.getReason()
                            : ex.getMessage();
            if (session.error == null) session.error = "该来源暂时不可读取，请检查文件组状态";
            session.status = "FAILED";
            closeContext(session);
        } finally {
            if (session.closed || System.currentTimeMillis() >= session.expires) {
                sessions.remove(session.id, session);
                closeContext(session);
            }
            session.lock.unlock();
        }
    }

    public JsonNode status(String id, Long owner) {
        Session session = require(id, owner);
        String status = session.status;
        ObjectNode result =
                json.createObjectNode()
                        .put("id", id)
                        .put("status", status)
                        .put("name", session.fileName)
                        .put("format", session.group.getFormat())
                        .put("expiresAt", Instant.ofEpochMilli(session.expires).toString());
        if ("READY".equals(status)) {
            lockSession(session);
            try {
                verify(session);
                result.set("descriptor", session.descriptor);
            } finally {
                session.lock.unlock();
            }
        }
        if ("FAILED".equals(status)) result.put("error", session.error);
        return result;
    }

    public JsonNode features(String id, Long owner, String layerKey, long after, int limit)
            throws IOException {
        Session session = ready(id, owner);
        enter(session);
        try {
            layer(session, layerKey);
            verify(session);
            if (!"VECTOR".equals(session.descriptor.path("kind").asText()))
                throw new IllegalArgumentException("不是矢量集合");
            JsonNode result = vectors.previewSource(Long.valueOf(layerKey), after, limit);
            verify(session);
            return result;
        } finally {
            leave(session);
        }
    }

    public Image image(
            String id,
            Long owner,
            Request options,
            double[] bbox,
            String crs,
            int width,
            int height)
            throws IOException {
        if (width < 1 || height < 1 || width > 2048 || height > 2048)
            throw new IllegalArgumentException("预览尺寸须为1至2048");
        Session session = ready(id, owner);
        enter(session);
        try {
            if (!"RASTER".equals(session.descriptor.path("kind").asText()))
                throw new IllegalArgumentException("不是栅格数据");
            JsonNode layer = layer(session, options.layerKey());
            if (!layer.path("imageAvailable").asBoolean())
                throw new IllegalArgumentException(
                        layer.path("imageReason").asText("该集合没有可显示的数值波段"));
            if (bbox != null && !layer.path("mapAvailable").asBoolean())
                throw new IllegalArgumentException("数据尚不具备地图定位信息");
            verify(session);
            if (bbox == null && options.band() == null && layer.path("nativeImage").asBoolean()) {
                byte[] original = session.context.previewOriginalImage();
                verify(session);
                String mime =
                        switch (session.group.getFormat()) {
                            case "JPG" -> "image/jpeg";
                            case "GIF" -> "image/gif";
                            default -> "image/png";
                        };
                return new Image(original, mime);
            }
            ObjectNode request =
                    renderOptions(options, layer)
                            .put("action", "RENDER")
                            .put("width", width)
                            .put("height", height);
            if (bbox != null) {
                request.set("bbox", json.valueToTree(bbox));
                request.put("crs", crs);
            }
            session.context.preview(request);
            byte[] bytes = session.context.previewImage();
            verify(session);
            return new Image(bytes, "image/png");
        } finally {
            leave(session);
        }
    }

    private ObjectNode renderOptions(Request options, JsonNode layer) {
        ObjectNode result = json.createObjectNode().put("layerKey", options.layerKey());
        if (options.band() != null) {
            boolean valid = false;
            for (JsonNode band : layer.path("bands"))
                if (band.path("band").asInt() == options.band()
                        && band.path("renderable").asBoolean()) valid = true;
            if (!valid) throw new IllegalArgumentException("选择的内容不可显示");
            result.putArray("bands").add(options.band());
        }
        return result;
    }

    private JsonNode layer(Session session, String key) {
        for (JsonNode layer : session.descriptor.path("layers"))
            if (layer.path("key").asText().equals(key)) return layer;
        throw new IllegalArgumentException("预览集合不存在");
    }

    private Session require(String id, Long owner) {
        Session session = sessions.get(id);
        if (session == null || session.closed || System.currentTimeMillis() >= session.expires)
            throw new ResponseStatusException(HttpStatus.GONE, "预览已过期，请重新打开");
        if (owner == null || !owner.equals(session.owner))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预览不存在");
        return session;
    }

    private Session ready(String id, Long owner) {
        Session session = require(id, owner);
        if (!"READY".equals(session.status))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "预览尚未就绪");
        return session;
    }

    private void verify(Session session) {
        GisFileGroup current = groups.getRequired(session.group.getId());
        if (!Objects.equals(current.getInputRevision(), session.group.getInputRevision())
                || !Objects.equals(current.getEntryFileId(), session.group.getEntryFileId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文件组来源已修订，请重新打开预览");
        if (session.context != null) session.context.verifySources();
        else if (session.descriptor != null) {
            var currentLayers = vectors.sourceLayers(session.group.getEntryFileId());
            for (JsonNode layer : session.descriptor.path("layers")) {
                if (currentLayers.stream()
                        .noneMatch(
                                value ->
                                        value.getId().toString().equals(layer.path("key").asText())
                                                && value.getResourceId()
                                                        .toString()
                                                        .equals(layer.path("resourceId").asText())))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "原始快照已变化，请重新打开预览");
            }
        }
    }

    private void lockSession(Session session) {
        boolean locked = false;
        try {
            locked = session.lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "当前预览正在读取，请稍后重试");
            if (session.closed || System.currentTimeMillis() >= session.expires)
                throw new ResponseStatusException(HttpStatus.GONE, "预览已关闭或到期");
        } catch (InterruptedException ex) {
            if (locked) session.lock.unlock();
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "预览读取已中断");
        } catch (RuntimeException ex) {
            if (locked) session.lock.unlock();
            throw ex;
        }
    }

    private void enter(Session session) {
        lockSession(session);
        try {
            if (!rendering.tryAcquire(5, TimeUnit.SECONDS))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "预览读取繁忙，请稍后重试");
        } catch (InterruptedException ex) {
            session.lock.unlock();
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "预览读取已中断");
        } catch (RuntimeException ex) {
            session.lock.unlock();
            throw ex;
        }
    }

    private void leave(Session session) {
        try {
            if (session.closed || System.currentTimeMillis() >= session.expires) {
                sessions.remove(session.id, session);
                closeContext(session);
            }
        } finally {
            rendering.release();
            session.lock.unlock();
        }
    }

    public void close(String id, Long owner) {
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        Session session = sessions.get(id);
        if (session == null) return;
        if (!owner.equals(session.owner))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预览不存在");
        // 正在读取时标记失效，由清理器在读取结束后释放，避免删除正在使用的文件。
        session.closed = true;
        if (session.lock.tryLock()) {
            try {
                sessions.remove(id, session);
                closeContext(session);
            } finally {
                session.lock.unlock();
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        for (Session session : sessions.values()) {
            if ((!session.closed && System.currentTimeMillis() < session.expires)
                    || !session.lock.tryLock()) continue;
            try {
                if (sessions.remove(session.id, session)) closeContext(session);
            } finally {
                session.lock.unlock();
            }
        }
    }

    private void closeContext(Session session) {
        if (session.context == null) return;
        try {
            session.context.close();
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("预览临时资源清理失败", ex);
        }
        session.context = null;
    }

    @PreDestroy
    public void stop() {
        preparation.shutdownNow();
        for (Session session : sessions.values()) {
            session.lock.lock();
            try {
                closeContext(session);
            } finally {
                session.lock.unlock();
            }
        }
        sessions.clear();
    }
}
