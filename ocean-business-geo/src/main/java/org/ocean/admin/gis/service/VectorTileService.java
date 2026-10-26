package org.ocean.admin.gis.service;

import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
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
@Service
public class VectorTileService {
    private static final String TILE_PREFIX = "gis:vector:dataset:tile:v3:";
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
    private final ConcurrentHashMap<String, TileContract> contracts = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, LocalTile> localTiles = new LinkedHashMap<>(64, .75f, true);
    private long localCacheBytes;

    private record LocalTile(byte[] body, long expiresAt) { }
    private record TileContract(GisVectorVO.MapDefinition definition, String policy) { }

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

    public GisVectorVO.MapDefinition definition(Long dataSetId) {
        return spatial(() -> {
            var definition = contract(requireState(dataSetId)).definition();
            var extent = mapper.dataSetExtent(dataSetId);
            return new GisVectorVO.MapDefinition(definition.dataSetId(),definition.tileVersion(),definition.dataRevision(),
                    definition.minZoom(),definition.nativeMaxZoom(),definition.displayMaxZoom(),definition.tileSize(),
                    definition.tiles(),extent == null ? null : json.readTree(extent));
        });
    }

    public byte[] getTile(Long dataSetId, String tileVersion, int z, int x, int y) {
        if (tileVersion == null || !tileVersion.matches("[a-f0-9]{64}")) throw bad("tileVersion无效");
        // 包括缓存命中也必须验证数据集仍可访问，不能从Redis绕过删除/权限检查。
        requireState(dataSetId);
        requireTile(z, x, y, 18);
        String key = TILE_PREFIX + dataSetId + ':' + tileVersion + ':' + z + ':' + x + ':' + y;
        byte[] cached = cachedTile(key);
        if (cached != null) return cached;
        var result = new CompletableFuture<byte[]>();
        var pending = inFlight.putIfAbsent(key, result);
        if (pending != null) return awaitTile(pending);
        try {
            byte[] body = spatial(() -> {
                var current = requireState(dataSetId);
                byte[] existing = cachedTile(key);
                if (existing != null) return existing;
                var contract = contract(current);
                if (!contract.definition().tileVersion().equals(tileVersion))
                    throw new ResponseStatusException(HttpStatus.GONE, "瓦片版本已过期，请重新读取地图定义");
                mapper.setTileQueryTimeout(properties.getQueryTimeoutMs());
                var payload = mapper.dataSetTile(dataSetId, contract.policy(),
                        z, x, y, properties.getMaxDecodedTileBytes());
                if (payload == null) throw unavailable("瓦片编码未返回结果，请稍后重试");
                if (payload.byteSize() > properties.getMaxDecodedTileBytes())
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "当前瓦片超过解码体积上限；未截断要素");
                if (payload.body() == null) throw unavailable("瓦片编码失败，请稍后重试");
                // HTTP层已启用MVT GZIP；不能用未压缩体积误判传输预算。
                if (payload.body().length > properties.getMaxTileBytes()
                        && gzipSize(payload.body()) > properties.getMaxTileBytes())
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "当前瓦片压缩后仍超过传输体积上限；未截断要素");
                cache(key, payload.body());
                return payload.body();
            });
            result.complete(body);
            return body;
        } catch (RuntimeException | Error ex) { result.completeExceptionally(ex); throw ex; }
        finally { inFlight.remove(key, result); }
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
        try {
            String cached = redis.opsForValue().get(key);
            if (cached == null) return null;
            redis.opsForZSet().add(CACHE_INDEX,key,System.currentTimeMillis());
            byte[] body = Base64.getDecoder().decode(cached);
            remember(key,body);
            return body;
        } catch (DataAccessException ex) { throw unavailable("瓦片缓存暂不可用"); }
    }

    private void cache(String key, byte[] bytes) {
        long now = System.currentTimeMillis();
        redis.execute(CACHE_WRITE,List.of(key,CACHE_INDEX),Base64.getEncoder().encodeToString(bytes),
                Integer.toString(properties.getTileCacheTtlSeconds()),Long.toString(now),
                Long.toString(now-properties.getTileCacheTtlSeconds()*1000L),Integer.toString(properties.getMaxCachedTiles()));
        remember(key,bytes);
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
