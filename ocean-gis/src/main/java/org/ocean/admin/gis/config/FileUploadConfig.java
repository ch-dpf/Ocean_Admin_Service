package org.ocean.admin.gis.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** GIS 文件上传配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "gis.upload")
@Validated
public class FileUploadConfig {
    @NotBlank
    private String basePath = "uploads/gis";
}
