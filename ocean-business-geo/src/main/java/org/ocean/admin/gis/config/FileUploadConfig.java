package org.ocean.admin.gis.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** GIS 文件上传配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "gis.upload")
@Validated
public class FileUploadConfig {
    @NotBlank
    private String basePath = "gis/uploads";

    @NotNull
    private DataSize maxFileSize = DataSize.ofGigabytes(25);

    @NotNull
    private DataSize maxBatchSize = DataSize.ofGigabytes(100);

    @NotNull
    private DataSize maxChunkSize = DataSize.ofMegabytes(128);

    @NotNull
    private DataSize streamBufferSize = DataSize.ofMegabytes(1);

    @NotNull
    private DataSize minFreeSpace = DataSize.ofGigabytes(20);

    @NotNull
    private Duration idleTimeout = Duration.ofHours(6);

    @NotNull
    private Duration cleanupInterval = Duration.ofHours(1);

    private int cleanupBatchSize = 100;
}
