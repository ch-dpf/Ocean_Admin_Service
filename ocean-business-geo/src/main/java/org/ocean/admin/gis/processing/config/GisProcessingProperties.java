package org.ocean.admin.gis.processing.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/** GIS 处理产物目录及服务器受控工作空间配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "gis.processing")
public class GisProcessingProperties {
    @NotBlank
    private String basePath = "gis/processing";
    @NotBlank
    private String imageryCachePath = "imagery/cache";
    // 是否启用图像优化
    private boolean imageryOptimizationEnabled = true;
    @Positive
    private long imageryPreprocessingMaxWindowPixels = 16_777_216L;
    @Positive
    private long imageryMaxTileCount = 200_000L;
    @Positive
    private int imageryOverviewMinSize = 512;
    // 超出轮廓限额时明确失败，不以矩形或简化几何替代实际覆盖区。
    @Min(4)
    private int metadataMaxBoundarySegments = 1_000_000;
    @Min(8)
    private int metadataMaxCoordinates = 2_000_000;
    @Min(1)
    @Max(16_777_216)
    private int metadataMaxWindowPixels = 262_144;
    @Min(0)
    @Max(9)
    private int archiveCompressionLevel = 1;
    private Map<String, String> workspaces = new LinkedHashMap<>();
    /** 每次接入最多扫描的目录和文件条目，超限时整次扫描失败，尚未登记任何文件。 */
    @Min(1)
    @Max(100000)
    private int workspaceMaxScanEntries = 10000;
}
