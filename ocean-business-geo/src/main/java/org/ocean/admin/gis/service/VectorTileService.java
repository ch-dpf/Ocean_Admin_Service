package org.ocean.admin.gis.service;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/** 同一数据版本生成一致的显示瓦片；原始要素查询和编辑仍由图层服务负责。 */
@Slf4j
@Service
public class VectorTileService {
    private static final String TILE_PREFIX = "gis:vector:dataset:tile:v4:";
    private static final String CACHE_INDEX = "gis:vector:dataset:tile:index";
    private static final DefaultRedisScript<String> CACHE_READ = new DefaultRedisScript<>("""
            if redis.call('STRLEN',KEYS[1]) > tonumber(ARGV[1]) then return false end
            return redis.call('GET',KEYS[1])
            """, String.class);
    private static final DefaultRedisScript<Long> CACHE_WRITE = new DefaultRedisScript<>("""
            redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2])
            redis.call('ZADD',KEYS[2],ARGV[3],KEYS[1])
            redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',ARGV[4])
            local excess=redis.call('ZCARD',KEYS[2])-tonumber(ARGV[5])
            if excess>0 then
              local old=redis.call('ZRANGE',KEYS[2],0,excess-1)
              for _,key in ipairs(old) do redis.call('UNLINK',key);redis.call('ZREM',KEYS[2],key) end
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
    private final VectorResourceService resources;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TileContract> contracts = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, LocalTile> localTiles = new LinkedHashMap<>(64, .75f, true);
    private long localCacheBytes;
    private volatile long redisRetryAfter;

    private record LocalTile(byte[] body, long expiresAt) { }
    private record TileContract(GisVectorVO.MapDefinition definition, String policy) { }

    public VectorTileService(GisVectorMapper mapper, VectorDataSetStyleService snapshots, VectorRenderPlanner planner, VectorTileProperties properties,
            @Qualifier("tileRedisTemplate") StringRedisTemplate redis, ObjectMapper json, PlatformTransactionManager transactions, VectorResourceService resources) {
        this.mapper = mapper;
        this.snapshots = snapshots;
        this.planner = planner;
        this.properties = properties;
        this.redis = redis;
        this.json = json;
        this.resources = resources;
        this.queries = new Semaphore(properties.getMaxConcurrentQueries(), true);
        this.admissions = new Semaphore(properties.getMaxConcurrentQueries() + properties.getMaxQueuedQueries(), true);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(Isolation.REPEATABLE_READ.value());
    }

    public GisVectorVO.MapDefinition definition(Long dataSetId) {
        requireState(dataSetId);
        resources.finishPending(dataSetId);
        return spatial(() -> {
            var definition = contract(requireState(dataSetId)).definition();
            var extent = mapper.dataSetExtent(dataSetId);
            return new GisVectorVO.MapDefinition(definition.dataSetId(),definition.tileVersion(),definition.dataRevision(),Long.toString(mapper.latestChange(dataSetId)),
                    definition.minZoom(),definition.nativeMaxZoom(),definition.displayMaxZoom(),definition.tileSize(),
                    definition.tiles(),extent == null ? null : json.readTree(extent));
        });
    }

    public byte[] getTile(Long dataSetId, String tileVersion, int z, int x, int y) {
        return getTile(dataSetId, tileVersion, z, x, y, 0);
    }

    public GisVectorVO.TileChanges changes(Long dataSetId, long after) {
        if (after < 0) throw bad("变更游标无效");
        requireState(dataSetId);
        resources.finishPending(dataSetId);
        return spatial(() -> {
            var definition = contract(requireState(dataSetId)).definition();
            long latest = mapper.latestChange(dataSetId);
            var nodes = json.readTree(mapper.changes(dataSetId, after));
            boolean reset = after > latest || nodes.size() > 256;
            var changes = new java.util.ArrayList<GisVectorVO.TileChange>();
            if (!reset) for (var node : nodes) changes.add(json.treeToValue(node, GisVectorVO.TileChange.class));
            return new GisVectorVO.TileChanges(Long.toString(latest), definition.tileVersion(), reset, changes);
        });
    }

    private byte[] getTile(Long dataSetId, String tileVersion, int z, int x, int y, int attempt) {
        long started = System.nanoTime();
        String[] phase = {"访问校验"};
        try {
            if (tileVersion == null || !tileVersion.matches("[a-f0-9]{64}")) throw bad("tileVersion无效");
            // 包括缓存命中也必须验证数据集仍可访问，不能从Redis绕过删除/权限检查。
            requireState(dataSetId);
            resources.finishPending(dataSetId);
            requireTile(z, x, y, 18);
            var initial = contract(requireState(dataSetId));
            if (!initial.definition().tileVersion().equals(tileVersion))
                throw new ResponseStatusException(HttpStatus.GONE, "瓦片规则已更新，请重新读取地图定义");
            var policy = json.readTree(initial.policy());
            double margin = policy.path("buffer").asDouble(256) / policy.path("extent").asDouble(8192);
            long revision = mapper.tileRevision(dataSetId,z,x,y,margin);
            // 修订记录可能刚刚提交；必须先完成其工作包写入，才能读取该修订的缓存或数据。
            resources.finishPending(dataSetId);
            String key = TILE_PREFIX + dataSetId + ':' + tileVersion + ':' + z + ':' + x + ':' + y + ':' + revision;
            phase[0] = "缓存读取";
            byte[] cached = cachedTile(key);
            if (cached != null) return cached;
            var result = new CompletableFuture<byte[]>();
            var pending = inFlight.putIfAbsent(key, result);
            phase[0] = "同瓦片等待";
            if (pending != null) return awaitTile(pending);
            try {
                phase[0] = "空间查询排队";
                byte[] body = spatial(() -> {
                    phase[0] = "数据版本校验";
                    var current = requireState(dataSetId);
                    phase[0] = "缓存复查";
                    byte[] existing = cachedTile(key);
                    if (existing != null) return existing;
                    phase[0] = "数据版本校验";
                    var contract = contract(current);
                    if (!contract.definition().tileVersion().equals(tileVersion))
                        throw new ResponseStatusException(HttpStatus.GONE, "瓦片版本已过期，请重新读取地图定义");
                    mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
                    GisVectorVO.TilePayload payload;
                    boolean packaged = mapper.hasPackageLayers(dataSetId);
                    phase[0] = packaged ? "GPKG读取编码" : "PostGIS查询编码";
                    long queryStarted = System.nanoTime();
                    if (packaged) {
                        try {
                            byte[] encoded=resources.tile(dataSetId,null,z,x,y,properties.getMaxDecodedTileBytes());
                            payload=new GisVectorVO.TilePayload(encoded,encoded.length);
                        } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"读取矢量文件失败",ex); }
                    } else payload=mapper.dataSetTile(dataSetId, contract.policy(),z,x,y,properties.getMaxDecodedTileBytes());
                    long queryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queryStarted);
                    if (queryMs >= 1000) log.info("动态瓦片慢查询: dataset={}, tile={}/{}/{}, phase={}, queryMs={}",
                            dataSetId,z,x,y,phase[0],queryMs);
                    phase[0] = "体积校验";
                    if (payload == null) throw unavailable("瓦片编码未返回结果，请稍后重试");
                    if (payload.byteSize() > properties.getMaxDecodedTileBytes())
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "当前瓦片超过解码体积上限；未截断要素");
                    if (payload.body() == null) throw unavailable("瓦片编码失败，请稍后重试");
                    // HTTP层已启用MVT GZIP；不能用未压缩体积误判传输预算。
                    if (payload.body().length > properties.getMaxTileBytes()
                            && gzipSize(payload.body()) > properties.getMaxTileBytes())
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "当前瓦片压缩后仍超过传输体积上限；未截断要素");
                    phase[0] = "事务提交";
                    return payload.body();
                });
                // 生成期间编辑可能已经提交；旧结果只能属于旧修订，不能进入最新缓存或覆盖地图。
                if (mapper.tileRevision(dataSetId,z,x,y,margin) != revision) {
                    if (attempt >= 2) throw new ResponseStatusException(HttpStatus.CONFLICT,"瓦片区域正在编辑，请重试");
                    byte[] latest = getTile(dataSetId,tileVersion,z,x,y,attempt+1);
                    result.complete(latest);
                    return latest;
                }
                // 空间查询事务先完成并释放执行名额，缓存网络等待不占用数据库连接。
                phase[0] = "缓存写入";
                cache(key, body);
                result.complete(body);
                return body;
            } catch (RuntimeException | Error ex) { result.completeExceptionally(ex); throw ex; }
            finally { inFlight.remove(key, result); }
        } catch (RuntimeException ex) {
            String sqlState = null;
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                if (root instanceof java.sql.SQLException sql) sqlState = sql.getSQLState();
                root = root.getCause();
            }
            if (root instanceof java.sql.SQLException sql) sqlState = sql.getSQLState();
            int status = ex instanceof ResponseStatusException response ? response.getStatusCode().value() : 500;
            if (status >= 500) {
                // 不记录SQL参数或要素内容；阶段、坐标和SQLSTATE足够关联失败请求。
                String reason = ex instanceof ResponseStatusException response ? response.getReason() : null;
                log.warn("动态瓦片失败: dataset={}, tile={}/{}/{}, phase={}, elapsedMs={}, status={}, reason={}, sqlState={}, cause={}",
                        dataSetId,z,x,y,phase[0],TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),
                        status,reason,sqlState,root.getClass().getSimpleName());
            }
            throw ex;
        }
    }

    private int gzipSize(byte[] body) {
        var compressed = new ByteArrayOutputStream(Math.min(body.length, properties.getMaxTileBytes()));
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(body);
        } catch (IOException ex) {
            throw new IllegalStateException("瓦片压缩体积计算失败", ex);
        }
        return compressed.size();
    }

    private void requireTile(int z, int x, int y, int maxZoom) {
        if (z < 0 || z > maxZoom || x < 0 || y < 0 || x >= (1L << z) || y >= (1L << z)) throw bad("瓦片坐标超出有效范围");
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
        byte[] memory = localTile(key);
        if (memory != null) return memory;
        if (System.nanoTime() < redisRetryAfter) return null;
        try {
            // 在服务端原子检查长度，确保读写两侧都遵守载荷上限。
            String cached = redis.execute(CACHE_READ, List.of(key), Integer.toString(properties.getMaxRedisValueBytes()));
            if (cached == null) return null;
            redis.opsForZSet().add(CACHE_INDEX,key,System.currentTimeMillis());
            byte[] body = Base64.getDecoder().decode(cached);
            remember(key,body);
            return body;
        } catch (DataAccessException ex) {
            redisRetryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            log.warn("矢量瓦片缓存读取失败，暂停共享缓存5秒并执行有界空间查询: cause={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private void cache(String key, byte[] bytes) {
        // 瓦片已完成版本和体积校验；共享缓存故障不能丢弃有效查询结果。
        remember(key,bytes);
        if (System.nanoTime() < redisRetryAfter
                || 4L * ((bytes.length + 2L) / 3L) > properties.getMaxRedisValueBytes()) return;
        long now = System.currentTimeMillis();
        long started = System.nanoTime();
        try {
            redis.execute(CACHE_WRITE,List.of(key,CACHE_INDEX),Base64.getEncoder().encodeToString(bytes),
                    Integer.toString(properties.getTileCacheTtlSeconds()),Long.toString(now),
                    Long.toString(now-properties.getTileCacheTtlSeconds()*1000L),Integer.toString(properties.getMaxCachedTiles()));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (elapsedMs >= 1000) log.info("动态瓦片缓存写入耗时: key={}, bytes={}, elapsedMs={}",key,bytes.length,elapsedMs);
        } catch (DataAccessException ex) {
            redisRetryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            log.warn("动态瓦片Redis缓存写入失败，返回已生成瓦片: key={}, bytes={}, elapsedMs={}, cause={}",
                    key,bytes.length,TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),root.getClass().getSimpleName());
        }
    }

    private GisVectorDataSetStyle requireState(Long dataSetId) {
        if (dataSetId == null || dataSetId < 1) throw bad("数据集ID无效");
        var state = mapper.tileState(dataSetId);
        if (state == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"矢量数据集不可用");
        if (state.getSchemaVersion() != 3) throw new ResponseStatusException(HttpStatus.CONFLICT,"该数据集未使用新版分类协议，请重新导入新数据集");
        return state;
    }

    private TileContract contract(GisVectorDataSetStyle state) {
        String key = state.getDataSetId() + ":" + state.getDataRevision() + ":" + state.getTemplateChecksum();
        if (contracts.size() > 128) contracts.clear();
        return contracts.computeIfAbsent(key, ignored -> {
            var snapshot = snapshots.require(state.getDataSetId(),false);
            return new TileContract(planner.definition(snapshot),json.writeValueAsString(planner.policy(snapshot)));
        });
    }

    private synchronized byte[] localTile(String key) {
        var tile = localTiles.get(key);
        if (tile == null) return null;
        if (tile.expiresAt() > System.nanoTime()) return tile.body();
        localTiles.remove(key);
        localCacheBytes -= tile.body().length;
        return null;
    }

    private synchronized void remember(String key, byte[] body) {
        if (body.length > properties.getMaxLocalCacheBytes()) return;
        var previous = localTiles.put(key,new LocalTile(body,System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getTileCacheTtlSeconds())));
        if (previous != null) localCacheBytes -= previous.body().length;
        localCacheBytes += body.length;
        var entries = localTiles.entrySet().iterator();
        while ((localCacheBytes > properties.getMaxLocalCacheBytes() || localTiles.size() > properties.getMaxCachedTiles()) && entries.hasNext()) {
            localCacheBytes -= entries.next().getValue().body().length;
            entries.remove();
        }
    }

    private ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason); }
    private ResponseStatusException unavailable(String reason) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,reason); }
}
