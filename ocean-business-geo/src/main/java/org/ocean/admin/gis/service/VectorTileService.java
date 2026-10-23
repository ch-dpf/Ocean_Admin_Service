package org.ocean.admin.gis.service;

import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private static final String CONTEXT_PREFIX = "gis:vector:dataset:context:";
    private static final String TILE_PREFIX = "gis:vector:dataset:tile:";
    private static final String CACHE_INDEX = "gis:vector:dataset:tile:index";
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
    private final VectorDataSetStyleService snapshots;
    private final VectorRenderPlanner planner;
    private final VectorTileProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Semaphore queries;
    private final Semaphore admissions;
    private final TransactionTemplate reads;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();

    public VectorTileService(GisVectorMapper mapper, VectorDataSetStyleService snapshots, VectorRenderPlanner planner, VectorTileProperties properties,
            StringRedisTemplate redis, ObjectMapper json, PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.snapshots = snapshots;
        this.planner = planner;
        this.properties = properties;
        this.redis = redis;
        this.json = json;
        this.queries = new Semaphore(properties.getMaxConcurrentQueries(), true);
        this.admissions = new Semaphore(properties.getMaxConcurrentQueries() + properties.getMaxQueuedQueries(), true);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(Isolation.REPEATABLE_READ.value());
    }

    public GisVectorVO.RenderContext createContext(Long dataSetId, GisVectorRequests.RenderContext request) {
        GisVectorVO.RenderContext context = spatial(() -> {
            mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
            return planner.plan(snapshots.require(dataSetId, false), request,
                    Instant.now().plusSeconds(properties.getContextTtlSeconds()));
        });
        String id = digest(json.writeValueAsString(java.util.Arrays.asList(context.dataSetId(),context.dataRevision(),
                context.displayRevision(),context.styleVersion(),context.bbox(),context.displayZoom(),context.widthPixels(),
                context.heightPixels(),context.gridPixels(),context.forceAggregation(),context.plans(),context.policy())));
        var result = new GisVectorVO.RenderContext(id,context.dataSetId(),context.dataRevision(),context.displayRevision(),
                context.styleVersion(),context.bbox(),context.displayZoom(),context.widthPixels(),context.heightPixels(),
                context.budget(),context.objectCount(),context.gridPixels(),context.forceAggregation(),context.maxZoom(),
                context.plans(),context.policy(),context.expiresAt());
        try { redis.opsForValue().set(CONTEXT_PREFIX + id,json.writeValueAsString(result),Duration.ofSeconds(properties.getContextTtlSeconds())); }
        catch (DataAccessException ex) { throw unavailable("渲染上下文存储暂不可用"); }
        return result;
    }

    public byte[] getTile(Long dataSetId, String contextId, int z, int x, int y) {
        var context = requireContext(dataSetId,contextId);
        requireTile(z,x,y,context.maxZoom());
        String key = TILE_PREFIX + contextId + ':' + z + ':' + x + ':' + y;
        byte[] cached = cachedTile(key);
        if (cached != null) return cached;
        var result = new CompletableFuture<byte[]>();
        var pending = inFlight.putIfAbsent(key,result);
        if (pending != null) return awaitTile(pending);
        try {
            byte[] body = spatial(() -> {
                var current = requireContext(dataSetId,contextId);
                byte[] existing = cachedTile(key);
                if (existing != null) return existing;
                mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
                var payload = mapper.dataSetTile(current,json.writeValueAsString(current.plans()),
                        json.writeValueAsString(current.policy()),z,x,y,properties.getMaxTileBytes());
                if (payload == null || payload.body() == null || payload.byteSize() > properties.getMaxTileBytes())
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"安全显示结果仍超出字节预算，请缩小视窗");
                cache(key,payload.body());
                return payload.body();
            });
            result.complete(body);
            return body;
        } catch (RuntimeException | Error ex) { result.completeExceptionally(ex); throw ex; }
        finally { inFlight.remove(key,result); }
    }

    public GisVectorVO.ClusterMembers getClusterFeatures(Long dataSetId, String contextId, String clusterId, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw bad("limit须为1至100");
        if (clusterId == null || !clusterId.matches("[0-9]{1,19}:-?[0-9]{1,13}:-?[0-9]{1,13}")) throw bad("clusterId无效");
        String[] parts = clusterId.split(":");
        long groupId, cx, cy;
        try { groupId=Long.parseLong(parts[0]); cx=Long.parseLong(parts[1]); cy=Long.parseLong(parts[2]); }
        catch (NumberFormatException ex) { throw bad("clusterId无效"); }
        String prefix = contextId + ':' + clusterId + ':';
        long after = parseCursor(cursor,prefix);
        return spatial(() -> {
            var context = requireContext(dataSetId,contextId);
            boolean known = false;
            for (var p : context.plans()) if (Long.toString(groupId).equals(p.path("groupId").asText())) known=true;
            if (!known) throw bad("聚合分组不属于该上下文");
            mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
            var rows = mapper.dataSetClusterMembers(context,json.writeValueAsString(context.plans()),
                    json.writeValueAsString(context.policy()),groupId,cx,cy,after,limit+1);
            if (rows.isEmpty() && after == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"聚合不存在");
            var page = new java.util.ArrayList<GisVectorVO.ClusterMember>();
            boolean more = false;
            long bytes = 0;
            for (var row : rows) {
                long size = row.getPropertiesJson() == null ? 4 : row.getPropertiesJson().getBytes(StandardCharsets.UTF_8).length;
                if (page.size() >= limit || bytes+size > 8L*1024*1024 && !page.isEmpty()) { more=true; break; }
                page.add(new GisVectorVO.ClusterMember(row.getLayerId().toString(),row.getId().toString(),
                        row.getPropertiesJson() == null ? null : json.readTree(row.getPropertiesJson()),row.getVersion()));
                bytes += size;
            }
            String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (prefix + page.getLast().featureId()).getBytes(StandardCharsets.UTF_8)) : null;
            return new GisVectorVO.ClusterMembers(clusterId,page,more,next,Long.toString(context.dataRevision()));
        });
    }

    private GisVectorVO.RenderContext requireContext(Long dataSetId, String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw bad("contextId无效");
        String stored;
        try { stored = redis.opsForValue().get(CONTEXT_PREFIX + id); }
        catch (DataAccessException ex) { throw unavailable("渲染上下文存储暂不可用"); }
        if (stored == null) throw new ResponseStatusException(HttpStatus.GONE,"渲染上下文已过期");
        var context = json.readValue(stored,GisVectorVO.RenderContext.class);
        if (!context.dataSetId().equals(dataSetId.toString())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"数据集中不存在该上下文");
        var snapshot = snapshots.require(dataSetId,false);
        if (snapshot.getDataRevision()!=context.dataRevision() || snapshot.getDisplayRevision()!=context.displayRevision()
                || snapshot.getStyleVersion()!=context.styleVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"数据、挂载或样式已变化，请重新创建上下文");
        if (context.expiresAt().isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE,"渲染上下文已过期");
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
