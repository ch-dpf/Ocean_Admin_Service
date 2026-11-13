package org.ocean.admin.gis.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/** GIS 托管资产根目录与外部工作空间配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "gis.storage")
public class GisStorageProperties {
    @NotBlank
    private String rootPath = "gis";

    /** 相对路径以托管根目录为基准；绝对路径用于外部原位接入。 */
    @NotNull
    private Map<String, String> workspaces = new LinkedHashMap<>(Map.of("default", "workspaces/default"));
}
