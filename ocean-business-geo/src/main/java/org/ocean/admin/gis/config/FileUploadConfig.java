package org.ocean.admin.gis.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 文件上传配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "gis.upload")
@Validated
public class FileUploadConfig {
    /** 上传文件的基础存储目录。 */
    @NotBlank
    private String basePath = "gis/uploads";

    /** 单个上传文件允许的最大大小。 */
    @NotNull
    private DataSize maxFileSize = DataSize.ofGigabytes(25);

    /** 单批上传文件允许的最大总大小。 */
    @NotNull
    private DataSize maxBatchSize = DataSize.ofGigabytes(100);

    /** 单个上传分片允许的最大大小。 */
    @NotNull
    private DataSize maxChunkSize = DataSize.ofMegabytes(128);

    /** 文件流读写使用的缓冲区大小。 */
    @NotNull
    private DataSize streamBufferSize = DataSize.ofMegabytes(1);

    /** 接收上传文件前要求存储空间至少保留的可用容量。 */
    @NotNull
    private DataSize minFreeSpace = DataSize.ofGigabytes(20);

    /** 未完成上传任务在无活动后可保留的最长时间。 */
    @NotNull
    private Duration idleTimeout = Duration.ofHours(6);

    /** 清理过期上传暂存文件的调度间隔。 */
    @NotNull
    private Duration cleanupInterval = Duration.ofHours(1);

}
