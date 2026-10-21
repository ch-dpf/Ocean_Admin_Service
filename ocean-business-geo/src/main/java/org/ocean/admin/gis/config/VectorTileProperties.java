package org.ocean.admin.gis.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 动态矢量瓦片的资源预算；不以任意截取要素代替完整查询。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "gis.vector.tiles")
public class VectorTileProperties {
    @Min(8) @Max(128)
    private int gridPixels = 64;
    @Min(0) @Max(22)
    private int maxZoom = 22;
    @Positive
    private int maxTileBytes = 2 * 1024 * 1024;
    @Min(1) @Max(16)
    private int maxConcurrentQueries = 4;
    @Min(0) @Max(128)
    private int maxQueuedQueries = 32;
    @Min(100) @Max(30000)
    private int queueWaitMs = 10000;
    @Min(100) @Max(60000)
    private int queryTimeoutMs = 10000;
    @Positive
    private int contextTtlSeconds = 1800;
    @Positive
    private int tileCacheTtlSeconds = 120;
    @Positive
    private int maxCachedTiles = 64;
    @Min(1000) @Max(240000)
    private int generalizationQueryTimeoutMs = 120000;

    @AssertTrue(message = "gridPixels 必须整除256，以保证跨瓦片聚合网格一致")
    public boolean isGridAligned() { return gridPixels > 0 && 256 % gridPixels == 0; }
}
