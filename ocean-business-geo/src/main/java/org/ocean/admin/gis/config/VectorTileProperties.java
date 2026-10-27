package org.ocean.admin.gis.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 压缩体积与解码体积分开限制，不以任意截取要素代替完整查询。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "gis.vector.tiles")
public class VectorTileProperties {
    /** 单个压缩矢量瓦片允许的最大字节数。 */
    @Positive
    private int maxTileBytes = 2 * 1024 * 1024;

    /** 单个矢量瓦片解码后允许的最大字节数。 */
    @Positive
    private int maxDecodedTileBytes = 16 * 1024 * 1024;

    /** 同时执行的矢量瓦片查询最大数量。 */
    @Min(1) @Max(16)
    private int maxConcurrentQueries = 4;

    /** 等待执行的矢量瓦片查询最大数量。 */
    @Min(0) @Max(128)
    private int maxQueuedQueries = 32;

    /** 查询进入执行队列时允许等待的最长时间，单位为毫秒。 */
    @Min(100) @Max(30000)
    private int queueWaitMs = 10000;

    /** 单次矢量瓦片查询的超时时间，单位为毫秒。 */
    @Min(100) @Max(60000)
    private int queryTimeoutMs = 10000;

    /** 矢量瓦片本地缓存的有效期，单位为秒。 */
    @Positive
    private int tileCacheTtlSeconds = 300;

    /** 本地缓存允许保存的矢量瓦片最大数量。 */
    @Positive
    private int maxCachedTiles = 64;

    /** 矢量瓦片本地缓存允许占用的最大字节数。 */
    @Positive
    private long maxLocalCacheBytes = 64L * 1024 * 1024;
}
