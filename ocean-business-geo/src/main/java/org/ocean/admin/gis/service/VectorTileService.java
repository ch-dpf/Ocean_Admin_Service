package org.ocean.admin.gis.service;

import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.mapper.GisVectorGeneralizationMapper;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 同一数据版本生成一致的显示瓦片；原始要素查询和编辑仍由图层服务负责。 */
@Service
public class VectorTileService {
    private static final String CONTEXT_PREFIX = "gis:vector:context:v2:";
    private static final String TILE_PREFIX = "gis:vector:tile:v2:";
    private static final String CACHE_INDEX = "gis:vector:tile:v2:index";
    private static final DefaultRedisScript<Long> CACHE_WRITE = new DefaultRedisScript<>("""
            redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2])
            redis.call('ZADD',KEYS[2],ARGV[3],KEYS[1])
            redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',ARGV[4])
            local excess=redis.call('ZCARD',KEYS[2])-tonumber(ARGV[5])
            if excess>0 then
              local old=redis.call('ZRANGE',KEYS[2],0,excess-1)
              for _,key in ipairs(old) do redis.call('DEL',key);redis.call('ZREM',KEYS[2],key) end
            end
            redis.call('EXPIRE',KEYS[2],tonumber(ARGV[2])*2)
            return 1
            """, Long.class);

    private final GisVectorMapper mapper;
    private final VectorLayerService layers;
    private final VectorTileProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final VectorGeneralizationService generalization;
    private final GisVectorGeneralizationMapper generalized;
    private final Semaphore queries;
    private final Semaphore admissions;
    private final TransactionTemplate reads;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();

    public VectorTileService(GisVectorMapper mapper, VectorLayerService layers, VectorTileProperties properties,
            StringRedisTemplate redis, ObjectMapper json, PlatformTransactionManager transactions,
            VectorGeneralizationService generalization, GisVectorGeneralizationMapper generalized) {
        this.mapper = mapper;
        this.layers = layers;
        this.properties = properties;
        this.redis = redis;
        this.json = json;
        this.generalization = generalization;
        this.generalized = generalized;
        this.queries = new Semaphore(properties.getMaxConcurrentQueries(), true);
        this.admissions = new Semaphore(properties.getMaxConcurrentQueries() + properties.getMaxQueuedQueries(), true);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(Isolation.REPEATABLE_READ.value());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.RenderContext createContext(Long layerId, GisVectorRequests.RenderContext request) {
        var layer = ready(layerId);
        if (request == null || request.fields() == null || request.fields().size() > 512) throw bad("fields 须为最多512项的数组");
        TreeSet<String> names = new TreeSet<>();
        for (String name : request.fields()) {
            if (name == null || name.isBlank() || name.length() > 255) throw bad("字段名须为1至255个字符");
            names.add(name);
        }
        List<String> fields = List.copyOf(names);
        if (json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8).length > 128 * 1024) throw bad("字段清单过大");
        int buffer = request.bufferPixels() == null ? 64 : request.bufferPixels();
        if (buffer < 0 || buffer > 2048) throw bad("bufferPixels 须为0至2048的整数");
        boolean aggregate = request.aggregatePoints() == null || request.aggregatePoints();
        String version = Long.toString(layer.dataVersion());
        var overview = generalization.plan(layerId,layer.dataVersion(),fields,buffer);
        String signature = json.writeValueAsString(List.of(layer.layerId(),version,fields,aggregate,
                properties.getGridPixels(),buffer,properties.getMaxZoom(),overview));
        String id = digest(signature);
        var context = new GisVectorVO.RenderContext(id,layer.layerId(),version,fields,aggregate,
                properties.getGridPixels(),buffer,properties.getMaxZoom(),Instant.now().plusSeconds(properties.getContextTtlSeconds()),
                overview.buildId(),overview.policyVersion(),overview.maxZoom(),overview.available(),overview.status(),overview.message());
        try {
            redis.opsForValue().set(CONTEXT_PREFIX + id,json.writeValueAsString(context),Duration.ofSeconds(properties.getContextTtlSeconds()));
        } catch (DataAccessException ex) { throw unavailable("渲染上下文存储暂不可用"); }
        return context;
    }

    public byte[] getTile(Long layerId, String contextId, int z, int x, int y) {
        var context = requireContext(layerId,contextId);
        requireTile(z,x,y,context.maxZoom());
        if (z<=context.overviewMaxZoom() && !context.overviewAvailable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,context.overviewMessage());
        }
        String key = TILE_PREFIX + contextId + ':' + z + ':' + x + ':' + y;
        byte[] cached = cachedTile(key);
        if (cached != null) return cached;
        var result = new CompletableFuture<byte[]>();
        var pending = inFlight.putIfAbsent(key,result);
        if (pending != null) return awaitTile(pending);
        try {
            byte[] body = spatial(() -> {
                // 排队期间可能发生编辑；获取执行槽位后再建立数据库快照并重新验证版本。
                requireContext(layerId,contextId);
                byte[] existing = cachedTile(key);
                if (existing != null) return existing;
                mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
                var payload = z<=context.overviewMaxZoom()
                        ? generalized.tile(Long.valueOf(context.generalizationBuildId()),layerId,z,x,y,json.writeValueAsString(context.fields()),
                            context.gridPixels(),context.bufferPixels(),context.aggregatePoints(),properties.getMaxTileBytes())
                        : mapper.vectorTile(layerId,z,x,y,json.writeValueAsString(context.fields()),
                            context.gridPixels(),context.bufferPixels(),context.aggregatePoints(),properties.getMaxTileBytes());
                if (payload == null || payload.body() == null || payload.byteSize() > properties.getMaxTileBytes()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"该区域显示内容过密，请放大地图或缩小显示范围");
                }
                cache(key,payload.body());
                return payload.body();
            });
            result.complete(body);
            return body;
        } catch (RuntimeException | Error ex) { result.completeExceptionally(ex); throw ex; }
        finally { inFlight.remove(key,result); }
    }

    public GisVectorVO.ClusterMembers getClusterFeatures(Long layerId, String contextId, String clusterId, int limit, String cursor) {
        var context = requireContext(layerId,contextId);
        if (!context.aggregatePoints()) throw bad("该上下文未启用点聚合");
        if (limit < 1 || limit > 100) throw bad("limit 须为1至100");
        if (clusterId == null || !clusterId.matches("[0-9]{1,2}:[0-9]{1,10}:[0-9]{1,10}:[a-f0-9]{64}")) throw bad("clusterId 无效");
        String[] parts = clusterId.split(":");
        int z = Integer.parseInt(parts[0]);
        if (z < 0 || z > context.maxZoom()) throw bad("聚合层级无效");
        long cx = Long.parseLong(parts[1]);
        long cy = Long.parseLong(parts[2]);
        long cells = (1L << z) * 256 / context.gridPixels();
        if (cx >= cells || cy >= cells) throw bad("聚合网格无效");
        String prefix = contextId + ':' + clusterId + ':';
        long after = parseCursor(cursor,prefix);
        int perTile = 256 / context.gridPixels();
        return spatial(() -> {
            requireContext(layerId,contextId);
            mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
            var rows = mapper.clusterMembers(layerId,z,(int)(cx / perTile),(int)(cy / perTile),
                    json.writeValueAsString(context.fields()),context.gridPixels(),context.bufferPixels(),cx,cy,parts[3],after,limit+1);
            if (rows.isEmpty() && after == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"聚合不存在或成员已变化");
            boolean more = rows.size() > limit;
            var page = rows.stream().limit(limit).map(row -> new GisVectorVO.ClusterMember(row.getId().toString(),
                    row.getPropertiesJson() == null ? null : json.readTree(row.getPropertiesJson()),row.getVersion())).toList();
            String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (prefix + page.getLast().featureId()).getBytes(StandardCharsets.UTF_8)) : null;
            return new GisVectorVO.ClusterMembers(clusterId,page,more,next,context.dataVersion());
        });
    }

    private GisVectorVO.Layer ready(Long layerId) {
        var layer = layers.detail(layerId);
        if (!"READY".equals(layer.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"图层尚未完成导入");
        return layer;
    }

    public GisVectorVO.GeneralizationMembers getRepresentationFeatures(Long layerId,String contextId,String representationId,int limit,String cursor) {
        var context=requireContext(layerId,contextId);
        if (!context.overviewAvailable() || representationId==null || !representationId.matches("[0-9]{1,2}:[a-f0-9]{32}")) throw bad("概化对象或上下文无效");
        if (limit<1 || limit>100) throw bad("limit 须为1至100");
        String[] parts=representationId.split(":");
        int z=Integer.parseInt(parts[0]);
        if (z>context.overviewMaxZoom()) throw bad("概化层级无效");
        String prefix=contextId+":"+representationId+":";
        long after=parseCursor(cursor,prefix);
        return spatial(() -> {
            requireContext(layerId,contextId);
            mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
            var rows=generalized.members(Long.valueOf(context.generalizationBuildId()),z,parts[1],layerId,after,limit+1);
            if (rows.isEmpty() && after==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"概化对象不存在或成员已变化");
            var page=rows.stream().limit(limit).map(row -> new GisVectorVO.ClusterMember(row.getId().toString(),
                    row.getPropertiesJson()==null?null:json.readTree(row.getPropertiesJson()),row.getVersion())).toList();
            boolean more=rows.size()>limit;
            String next=more?Base64.getUrlEncoder().withoutPadding().encodeToString((prefix+page.getLast().featureId()).getBytes(StandardCharsets.UTF_8)):null;
            return new GisVectorVO.GeneralizationMembers(representationId,page,more,next,context.dataVersion());
        });
    }

    private GisVectorVO.RenderContext requireContext(Long layerId, String id) {
        var layer = ready(layerId);
        if (id == null || !id.matches("[a-f0-9]{64}")) throw bad("contextId 无效");
        String stored;
        try { stored = redis.opsForValue().get(CONTEXT_PREFIX + id); }
        catch (DataAccessException ex) { throw unavailable("渲染上下文存储暂不可用"); }
        if (stored == null) throw new ResponseStatusException(HttpStatus.GONE,"渲染上下文已过期，请重新创建");
        var context = json.readValue(stored,GisVectorVO.RenderContext.class);
        if (!context.layerId().equals(layer.layerId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"图层内不存在该渲染上下文");
        if (!context.dataVersion().equals(Long.toString(layer.dataVersion()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"图层已编辑，请刷新数据版本及渲染上下文");
        }
        var current=generalization.plan(layerId,layer.dataVersion(),context.fields(),context.bufferPixels());
        if (!context.policyVersion().equals(current.policyVersion()) || !context.generalizationBuildId().equals(current.buildId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"概览版本已变化，请刷新渲染上下文");
        }
        if (context.expiresAt().isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE,"渲染上下文已过期，请重新创建");
        return context;
    }

    private void requireTile(int z, int x, int y, int maxZoom) {
        if (z < 0 || z > maxZoom || x < 0 || y < 0 || x >= (1L << z) || y >= (1L << z)) throw bad("瓦片坐标超出有效范围");
    }

    private long parseCursor(String cursor, String prefix) {
        if (cursor == null) return 0;
        try {
            if (cursor.length() > 512) throw new IllegalArgumentException();
            String value = new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
            if (!value.startsWith(prefix)) throw new IllegalArgumentException();
            long after = Long.parseLong(value.substring(prefix.length()));
            if (after < 1) throw new IllegalArgumentException();
            return after;
        } catch (IllegalArgumentException ex) { throw bad("cursor 与聚合或渲染上下文不匹配"); }
    }

    private <T> T spatial(Supplier<T> work) {
        if (!admissions.tryAcquire()) throw unavailable("空间查询队列已满，请稍后重试");
        boolean acquired = false;
        try {
            acquired = queries.tryAcquire(properties.getQueueWaitMs(),TimeUnit.MILLISECONDS);
            if (!acquired) throw unavailable("空间查询排队超时，请稍后重试");
            // 排队不占用数据库连接，只有正在执行的空间查询才打开事务。
            return reads.execute(status -> work.get());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("空间查询等待已中断");
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"空间查询超时或存储服务暂不可用，请稍后重试",ex);
        } finally {
            if (acquired) queries.release();
            admissions.release();
        }
    }

    private byte[] awaitTile(CompletableFuture<byte[]> pending) {
        try {
            return pending.get(properties.getQueueWaitMs() + properties.getQueryTimeoutMs() + 6000L,TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("瓦片等待已中断");
        } catch (TimeoutException ex) { throw unavailable("瓦片等待超时，请稍后重试"); }
        catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(ex.getCause());
        }
    }

    private byte[] cachedTile(String key) {
        try {
            String cached = redis.opsForValue().get(key);
            if (cached == null) return null;
            redis.opsForZSet().add(CACHE_INDEX,key,System.currentTimeMillis());
            return Base64.getDecoder().decode(cached);
        } catch (DataAccessException ex) { throw unavailable("瓦片缓存暂不可用"); }
    }

    private void cache(String key, byte[] bytes) {
        long now = System.currentTimeMillis();
        redis.execute(CACHE_WRITE,List.of(key,CACHE_INDEX),Base64.getEncoder().encodeToString(bytes),
                Integer.toString(properties.getTileCacheTtlSeconds()),Long.toString(now),
                Long.toString(now-properties.getTileCacheTtlSeconds()*1000L),Integer.toString(properties.getMaxCachedTiles()));
    }

    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason); }
    private ResponseStatusException unavailable(String reason) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,reason); }
}
