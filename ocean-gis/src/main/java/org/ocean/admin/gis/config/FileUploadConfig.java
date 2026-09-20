package org.ocean.admin.gis.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** GIS 文件上传配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "gis.upload")
@Validated
public class FileUploadConfig {
    @NotBlank
    private String basePath = "uploads/gis";

    /** 上传会话在 Redis 中的最长等待时间。 */
    private Duration sessionTtl = Duration.ofHours(2);
}
