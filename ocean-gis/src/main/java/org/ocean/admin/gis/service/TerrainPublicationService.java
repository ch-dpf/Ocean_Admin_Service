package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.entity.GisTerrainPublication;
import org.ocean.admin.gis.mapper.GisTerrainPublicationMapper;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.vo.GisTerrainPublicationVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** 管理地形发布记录，并将公开服务编码解析为受控的本地切片目录。 */
@Service
@RequiredArgsConstructor
public class TerrainPublicationService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final String DISABLED = "DISABLED";
    private static final String TERRAIN_PREFIX = "terrain/";
    private static final Set<String> PUBLICATION_STATUSES = Set.of(PUBLISHED, DISABLED);

    private final GisTerrainPublicationMapper publicationMapper;
    private final GisTaskService gisTaskService;
    private final GisProcessingStorageService processingStorageService;
    private final Map<String, Path> publishedRoots = new ConcurrentHashMap<>();

    @Value("${gis.publication.public-base-url:http://localhost:8090}")
    private String publicBaseUrl;

    public PageResult<List<GisTerrainPublicationVO>> getPublicationPage(
            Integer current,
            Integer size,
            String serviceCode,
            String status,
            Long dataSetId,
            LocalDateTime publishTimeStart,
            LocalDateTime publishTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedServiceCode = trimToNull(serviceCode);
        if (normalizedServiceCode != null) {
            normalizedServiceCode = normalizedServiceCode.toUpperCase(Locale.ROOT);
        }
        String normalizedStatus = trimToNull(status);
        if (normalizedStatus != null) {
            normalizedStatus = normalizedStatus.toUpperCase(Locale.ROOT);
        }
        if (normalizedStatus != null && !PUBLICATION_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("发布状态只能为 PUBLISHED 或 DISABLED");
        }
        if (publishTimeStart != null && publishTimeEnd != null
                && publishTimeStart.isAfter(publishTimeEnd)) {
            throw new IllegalArgumentException("发布开始时间不能晚于发布结束时间");
        }

        LambdaQueryWrapper<GisTerrainPublication> query =
                new LambdaQueryWrapper<GisTerrainPublication>()
                        .like(normalizedServiceCode != null,
                                GisTerrainPublication::getServiceCode, normalizedServiceCode)
                        .eq(normalizedStatus != null,
                                GisTerrainPublication::getStatus, normalizedStatus)
                        .eq(dataSetId != null, GisTerrainPublication::getDataSetId, dataSetId)
                        .ge(publishTimeStart != null,
                                GisTerrainPublication::getPublishTime, publishTimeStart)
                        .le(publishTimeEnd != null,
                                GisTerrainPublication::getPublishTime, publishTimeEnd)
                        .orderByDesc(GisTerrainPublication::getPublishTime);
        Page<GisTerrainPublication> page = publicationMapper.selectPage(
                new Page<>(currentPage, pageSize), query);
        List<GisTerrainPublicationVO> records = page.getRecords().stream()
                .map(this::toVO)
                .toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisTerrainPublicationVO publish(Long sourceTaskId) {
        GisTask sourceTask = gisTaskService.getRequired(sourceTaskId);
        validateSourceTask(sourceTask);
        Path tilesRoot = validateTiles(sourceTask.getOutputKey());

        GisTerrainPublication publication = findBySourceTaskId(sourceTaskId);
        if (publication != null && PUBLISHED.equals(publication.getStatus())) {
            publishedRoots.put(publication.getServiceCode(), tilesRoot);
            return toVO(publication);
        }

        LocalDateTime now = LocalDateTime.now();
        GisTask publishTask = createCompletedPublishTask(sourceTask, now);
        gisTaskService.insert(publishTask);

        if (publication == null) {
            publication = new GisTerrainPublication();
            publication.setServiceCode(generateServiceCode());
            publication.setSourceTaskId(sourceTask.getId());
            publication.setDataSetId(sourceTask.getDataSetId());
            publication.setOutputKey(sourceTask.getOutputKey());
            publication.setDeleted(0);
        }
        publication.setPublishTaskId(publishTask.getId());
        publication.setStatus(PUBLISHED);
        publication.setPublishTime(now);
        publication.setUpdateTime(now);

        int affected = publication.getId() == null
                ? publicationMapper.insert(publication)
                : publicationMapper.updateById(publication);
        if (affected != 1) {
            throw new IllegalStateException("地形发布记录保存失败");
        }
        publishedRoots.put(publication.getServiceCode(), tilesRoot);
        return toVO(publication);
    }

    public GisTerrainPublicationVO get(String serviceCode) {
        return toVO(getRequired(serviceCode));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisTerrainPublicationVO disable(String serviceCode) {
        GisTerrainPublication publication = getRequired(serviceCode);
        if (!DISABLED.equals(publication.getStatus())) {
            publication.setStatus(DISABLED);
            publication.setUpdateTime(LocalDateTime.now());
            if (publicationMapper.updateById(publication) != 1) {
                throw new IllegalStateException("地形服务停用失败: " + serviceCode);
            }
        }
        publishedRoots.remove(serviceCode);
        return toVO(publication);
    }

    /** 解析公开请求中的相对路径；空路径表示请求 layer.json。 */
    public Path resolvePublishedFile(String serviceCode, String relativePath) {
        Path tilesRoot = publishedRoots.computeIfAbsent(serviceCode, this::loadPublishedRoot);
        String normalizedRelative = relativePath == null || relativePath.isBlank()
                ? "layer.json"
                : relativePath.replace('\\', '/');
        if (normalizedRelative.startsWith("/")) {
            normalizedRelative = normalizedRelative.substring(1);
        }
        Path file = tilesRoot.resolve(normalizedRelative).normalize();
        if (!file.startsWith(tilesRoot) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("地形资源不存在");
        }
        return file;
    }

    private Path loadPublishedRoot(String serviceCode) {
        GisTerrainPublication publication = getRequired(serviceCode);
        if (!PUBLISHED.equals(publication.getStatus())) {
            throw new IllegalStateException("地形服务已停用");
        }
        return validateTiles(publication.getOutputKey());
    }

    private GisTerrainPublication getRequired(String serviceCode) {
        String normalized = normalizeServiceCode(serviceCode);
        GisTerrainPublication publication = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisTerrainPublication>()
                        .eq(GisTerrainPublication::getServiceCode, normalized));
        if (publication == null) {
            throw new IllegalArgumentException("地形服务不存在: " + normalized);
        }
        return publication;
    }

    private GisTerrainPublication findBySourceTaskId(Long sourceTaskId) {
        return publicationMapper.selectOne(new LambdaQueryWrapper<GisTerrainPublication>()
                .eq(GisTerrainPublication::getSourceTaskId, sourceTaskId));
    }

    private void validateSourceTask(GisTask task) {
        if (!Long.valueOf(2L).equals(task.getTaskType())
                || !"TERRAIN".equals(task.getProcessingType())) {
            throw new IllegalArgumentException("只能发布地形切片任务: " + task.getId());
        }
        if (!"COMPLETED".equals(task.getTaskStatus())) {
            throw new IllegalStateException("只有 COMPLETED 状态的地形切片任务可以发布");
        }
        if (task.getOutputKey() == null || !task.getOutputKey().startsWith(TERRAIN_PREFIX)) {
            throw new IllegalArgumentException("地形切片任务缺少合法的产物 Key");
        }
    }

    private Path validateTiles(String outputKey) {
        Path tilesRoot = processingStorageService.resolveTiles(outputKey);
        if (!Files.isRegularFile(tilesRoot.resolve("layer.json"))) {
            throw new IllegalStateException("地形切片缺少 layer.json: " + tilesRoot);
        }
        try (Stream<Path> files = Files.walk(tilesRoot)) {
            if (files.noneMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".terrain"))) {
                throw new IllegalStateException("地形切片目录中没有 .terrain 文件: " + tilesRoot);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法检查地形切片目录: " + tilesRoot, ex);
        }
        return tilesRoot;
    }

    private GisTask createCompletedPublishTask(GisTask sourceTask, LocalDateTime now) {
        GisTask task = new GisTask();
        task.setTaskNo("GIS_TERRAIN_PUBLISH_"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT));
        task.setTaskName("发布地形服务：" + sourceTask.getTaskName());
        task.setTaskType(3L);
        task.setPriority(0);
        task.setTotalCount(1L);
        task.setCompletedCount(1L);
        task.setFailedCount(0L);
        task.setTaskStatus("COMPLETED");
        task.setCurrentStage("FINISHED");
        task.setDataSetId(sourceTask.getDataSetId());
        task.setProcessingType("TERRAIN");
        task.setSourceFileMetaId(sourceTask.getSourceFileMetaId());
        task.setOutputKey(sourceTask.getOutputKey());
        task.setParentTaskId(sourceTask.getId());
        task.setRootTaskId(sourceTask.getRootTaskId() == null
                ? sourceTask.getId() : sourceTask.getRootTaskId());
        task.setStartTime(now);
        task.setFinishTime(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        return task;
    }

    private String generateServiceCode() {
        return "TRN_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private String normalizeServiceCode(String serviceCode) {
        if (serviceCode == null
                || !serviceCode.matches("TRN_[A-Fa-f0-9]{20}")) {
            throw new IllegalArgumentException("非法地形服务编码");
        }
        return serviceCode.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private GisTerrainPublicationVO toVO(GisTerrainPublication publication) {
        return GisTerrainPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .sourceTaskId(publication.getSourceTaskId())
                .publishTaskId(publication.getPublishTaskId())
                .dataSetId(publication.getDataSetId())
                .status(publication.getStatus())
                .serviceUrl(buildServiceUrl(publication.getServiceCode()))
                .publishTime(publication.getPublishTime())
                .updateTime(publication.getUpdateTime())
                .build();
    }

    private String buildServiceUrl(String serviceCode) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("gis.publication.public-base-url 配置无效", ex);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalStateException("gis.publication.public-base-url 必须是 HTTP(S) 地址");
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/terrain/" + serviceCode + "/";
    }
}
