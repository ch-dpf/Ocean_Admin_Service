package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import org.ocean.admin.gis.progress.ProgressSnapshot;
import tools.jackson.databind.JsonNode;

/** GIS 文件元数据查询响应对象；嵌套类型用于工作区接入请求及结果。 */
@Data
public class GisFileMetaVO {

    @Schema(description = "文件元数据 ID", accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Schema(description = "所属数据集 ID", accessMode = Schema.AccessMode.READ_ONLY)
    private Long dataSetId;
    @Schema(description = "文件来源：UPLOAD 或 WORKSPACE", accessMode = Schema.AccessMode.READ_ONLY)
    private String sourceType;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private String workspaceCode;
    @Schema(description = "工作区内的相对文件路径", accessMode = Schema.AccessMode.READ_ONLY)
    private String relativePath;
    @Schema(description = "登记时的源文件修改时间，Unix 毫秒", accessMode = Schema.AccessMode.READ_ONLY)
    private Long sourceModifiedAt;
    @Schema(description = "矢量归组方式", allowableValues = {"AUTO", "UNKNOWN"}, accessMode = Schema.AccessMode.READ_ONLY)
    private String vectorPlacement;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Long vectorDatasetStyleId;

    @Schema(description = "数据类别：0-影像数据、1-地形数据、2-矢量数据",
            allowableValues = {"0", "1", "2"}, accessMode = Schema.AccessMode.READ_ONLY)
    private Long categoryId;

    @Schema(description = "原始文件名", accessMode = Schema.AccessMode.READ_ONLY)
    private String originalName;

    @Schema(description = "系统存储文件名；失败且尚未形成存储对象时为空", accessMode = Schema.AccessMode.READ_ONLY)
    private String storageName;

    @Schema(description = "相对存储路径或对象存储 Key；失败且尚未形成存储对象时为空", accessMode = Schema.AccessMode.READ_ONLY)
    private String storageKey;

    @Schema(description = "存储类型", allowableValues = {"LOCAL", "MINIO", "S3"}, example = "LOCAL", accessMode = Schema.AccessMode.READ_ONLY)
    private String storageType;

    @Schema(description = "文件扩展名", example = "tif", accessMode = Schema.AccessMode.READ_ONLY)
    private String extension;

    @Schema(description = "文件大小（字节）", accessMode = Schema.AccessMode.READ_ONLY)
    private Long sizeBytes;

    @Schema(description = "文件 SHA-256 摘要", accessMode = Schema.AccessMode.READ_ONLY)
    private String sha256;

    @Schema(description = "登记人 ID", accessMode = Schema.AccessMode.READ_ONLY)
    private Long createdBy;

    @Schema(description = "源文件状态", allowableValues = {"PENDING", "READY", "FAILED"}, example = "READY", accessMode = Schema.AccessMode.READ_ONLY)
    private String fileStatus;

    @Schema(description = "失败文件资源清理状态",
            allowableValues = {"NOT_REQUIRED", "PENDING", "COMPLETED", "FAILED"},
            accessMode = Schema.AccessMode.READ_ONLY)
    private String cleanupStatus;

    @Schema(description = "处理错误信息", accessMode = Schema.AccessMode.READ_ONLY)
    private String errorMessage;

    @Schema(description = "元数据状态：WAITING等待整批上传或扫描结束，QUEUED等待解析，RUNNING解析中，COMPLETED、FAILED、NOT_APPLICABLE", accessMode = Schema.AccessMode.READ_ONLY)
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
            @Schema(description = "源像元在 Web Mercator 赤道处的名义 X 分辨率，m/像元；无法识别源 CRS 时为有效域投影采样间距") Double resolutionX,
            @Schema(description = "源像元在 Web Mercator 赤道处的名义 Y 分辨率，m/像元；无法识别源 CRS 时为有效域投影采样间距") Double resolutionY,
            @Schema(description = "栅格分辨率单位固定为 m，源单位见 sourceUnit") String resolutionUnit,
            String tileMatrixSetId, Integer tileWidth, Integer tileHeight,
            Integer recommendedMaxZoom, JsonNode calculationDetails, JsonNode extraMetadata) { }

    @Schema(description = "创建时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime createTime;

    @Schema(description = "更新时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime updateTime;

    public record WorkspaceRequest(
            @NotBlank @Size(max = 64) String workspaceCode,
            @NotBlank @Size(max = 1000) String relativePath,
            @Schema(description = "目录接入是否包含所有层级子目录，默认false", defaultValue = "false") Boolean includeSubdirectories,
            @Pattern(regexp = "AUTO|UNKNOWN") String vectorPlacement) { }

    public record WorkspaceItem(Long fileMetaId, String relativePath, String status, String errorMessage) { }

    public record WorkspaceSubmission(Long dataSetId, ProgressSnapshot.Reference progress) { }
}
