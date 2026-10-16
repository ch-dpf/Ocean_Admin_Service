package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** GIS 文件元数据请求及响应对象。 */
@Data
public class GisFileMetaVO {

    @Schema(description = "文件元数据 ID", accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Schema(description = "所属数据集 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long dataSetId;

    @Schema(description = "数据类别：0-影像数据、1-地形数据、2-矢量数据",
            allowableValues = {"0", "1", "2"}, accessMode = Schema.AccessMode.READ_ONLY)
    private Long categoryId;

    @Schema(description = "用户上传时的文件名", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(max = 255, message = "原始文件名长度不能超过255个字符")
    private String originalName;

    @Schema(description = "系统存储文件名；失败且尚未形成存储对象时为空")
    @Size(max = 255, message = "存储文件名长度不能超过255个字符")
    private String storageName;

    @Schema(description = "相对存储路径或对象存储 Key；失败且尚未形成存储对象时为空")
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

    @Schema(description = "失败文件资源清理状态",
            allowableValues = {"NOT_REQUIRED", "PENDING", "COMPLETED", "FAILED"},
            accessMode = Schema.AccessMode.READ_ONLY)
    private String cleanupStatus;

    @Schema(description = "处理错误信息")
    @Size(max = 1000, message = "错误信息长度不能超过1000个字符")
    private String errorMessage;

    @Schema(description = "元数据提取状态", accessMode = Schema.AccessMode.READ_ONLY)
    private String metadataStatus;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Long metadataVersion;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private String metadataErrorCode;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private String metadataErrorMessage;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime metadataExtractedAt;
    @Schema(description = "各图层空间信息；geographicExtent 为 EPSG:4326 WKT", accessMode = Schema.AccessMode.READ_ONLY)
    private List<SpatialReference> spatialReferences;

    public record SpatialReference(String layerKey, String layerName, String dataKind,
            String sourceCrsCode, String sourceCrsWkt, String sourceUnit, JsonNode sourceExtent,
            String geographicExtent, String extentKind, Double west, Double south, Double east, Double north,
            Integer rasterWidth, Integer rasterHeight, Integer bandCount,
            @Schema(description = "EPSG:3857 投影 X 像元轴分辨率，m/像元；超出有效域时为空") Double resolutionX,
            @Schema(description = "EPSG:3857 投影 Y 像元轴分辨率，m/像元；超出有效域时为空") Double resolutionY,
            @Schema(description = "栅格分辨率单位固定为 m，源单位见 sourceUnit") String resolutionUnit,
            String tileMatrixSetId, Integer tileWidth, Integer tileHeight,
            Integer recommendedMaxZoom, JsonNode calculationDetails, JsonNode extraMetadata) { }

    @Schema(description = "创建时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime createTime;

    @Schema(description = "更新时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime updateTime;
}
