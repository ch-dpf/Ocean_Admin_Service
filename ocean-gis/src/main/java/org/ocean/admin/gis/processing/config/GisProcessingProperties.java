package org.ocean.admin.gis.processing.config;

import jakarta.validation.constraints.NotBlank;
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
    private boolean imageryOptimizationEnabled = true;
    @Positive
    private long imageryOptimizationMinFileSize = 512L * 1024 * 1024;
    @Positive
    private long imageryOptimizationMinPixels = 100_000_000L;
    @Positive
    private int imageryOverviewMinSize = 512;
    private Map<String, String> workspaces = new LinkedHashMap<>();
}
