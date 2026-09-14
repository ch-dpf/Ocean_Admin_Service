package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** GIS 文件元数据请求及响应对象。 */
@Data
public class GisFileMetaVO {

    @Schema(description = "文件元数据 ID", accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Schema(description = "所属数据集 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long dataSetId;

    @Schema(description = "所属上传任务 ID")
    private Long taskId;

    @Schema(description = "用户上传时的文件名", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(max = 255, message = "原始文件名长度不能超过255个字符")
    private String originalName;

    @Schema(description = "系统存储文件名", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(max = 255, message = "存储文件名长度不能超过255个字符")
    private String storageName;

    @Schema(description = "相对存储路径或对象存储 Key", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(max = 1000, message = "存储 Key 长度不能超过1000个字符")
    private String storageKey;

    @Schema(description = "存储类型", allowableValues = {"LOCAL", "MINIO", "S3"}, example = "LOCAL")
    @Pattern(regexp = "(?i)LOCAL|MINIO|S3", message = "存储类型只能为 LOCAL、MINIO 或 S3")
    private String storageType;

    @Schema(description = "文件扩展名", example = "tif")
    @Size(max = 32, message = "文件扩展名长度不能超过32个字符")
    private String extension;

    @Schema(description = "文件大小（字节）", requiredMode = Schema.RequiredMode.REQUIRED)
    @PositiveOrZero(message = "文件大小不能小于0")
    private Long sizeBytes;

    @Schema(description = "文件 SHA-256 摘要")
    @Pattern(regexp = "(?i)[0-9a-f]{64}", message = "SHA-256 必须为64位十六进制字符串")
    private String sha256;

    @Schema(description = "上传人 ID")
    private Long uploadedBy;

    @Schema(description = "上传状态", allowableValues = {"PENDING", "READY", "FAILED"}, example = "READY")
    @Pattern(regexp = "(?i)PENDING|READY|FAILED", message = "上传状态只能为 PENDING、READY 或 FAILED")
    private String uploadStatus;

    @Schema(description = "处理错误信息")
    @Size(max = 1000, message = "错误信息长度不能超过1000个字符")
    private String errorMessage;

    @Schema(description = "创建时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime createTime;

    @Schema(description = "更新时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime updateTime;
}
