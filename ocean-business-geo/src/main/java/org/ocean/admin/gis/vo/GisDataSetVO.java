package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 数据集VO
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Data
public class GisDataSetVO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "数据集名称")
    @NotNull(message = "数据集不能为空")
    private String dataSetName;

    @Schema(
            description = "数据类别：0-影像数据、1-地形数据、2-矢量数据",
            allowableValues = {"0", "1", "2"},
            example = "0"
    )
    @NotNull(message = "数据类别不能为空")
    private Long categoryId;
    @Schema(description = "矢量样式快照摘要，仅详情返回，不含规则与几何", accessMode = Schema.AccessMode.READ_ONLY)
    private VectorStyleSummary vectorStyle;
    public record VectorStyleSummary(String snapshotId, String templateId, int templateVersion,
            long styleVersion, long dataRevision, long displayRevision) { }

    @Schema(description = "数据集描述")
    private String description;

    @Schema(description = "数据集编码", accessMode = Schema.AccessMode.READ_ONLY)
    private String dataSetCode;

    @Schema(description = "未删除且源状态为READY的管理文件数量，包含工作区引用，与总大小同口径", accessMode = Schema.AccessMode.READ_ONLY)
    private Integer fileCount;

    @Schema(description = "未删除且源状态为READY的管理文件总大小（字节），包含工作区引用及配套文件，与元数据提取状态无关",
            accessMode = Schema.AccessMode.READ_ONLY)
    private Long totalSizeBytes;

    @Schema(description = "源状态为READY的未删除文件及其轮廓处理状态摘要", accessMode = Schema.AccessMode.READ_ONLY)
    private FileSummary fileSummary;

    @Schema(description = "分页接口直接返回已保存的文件代表点，每个可定位文件一个点；其他接口为 null",
            accessMode = Schema.AccessMode.READ_ONLY)
    private List<FilePoint> filePoints;

    @Schema(description = "EPSG:4326 GeoJSON FeatureCollection；仅单数据集轮廓接口返回，其他查询为 null",
            accessMode = Schema.AccessMode.READ_ONLY)
    private FootprintCollection footprints;

    @Data
    public static class FilePoint {
        private Long dataSetId;
        private Long fileId;
        private String fileName;
        @Schema(description = "经度，EPSG:4326")
        private Double longitude;
        @Schema(description = "纬度，EPSG:4326")
        private Double latitude;
        private String layerKey;
        @Schema(description = "代表点来源范围类型；VECTOR_ENVELOPE 表示来自包围范围，不能保证位于真实矢量要素内")
        private String extentKind;
    }

    @Data
    public static class FileSummary {
        private Long dataSetId;
        private long managedFileCount;
        private long footprintFileCount;
        private long pendingMetadataFileCount;
        private long failedMetadataFileCount;
        private long notApplicableFileCount;
    }

    public record FootprintCollection(String type, List<FootprintFeature> features) { }

    public record FootprintFeature(String type, String id, JsonNode geometry, FootprintProperties properties) { }

    public record FootprintProperties(Long fileId, String fileName, String layerKey, String extentKind,
            @Schema(description = "是否应用拓扑保持简化，不表示顶点数必定减少") boolean simplified,
            @Schema(description = "简化容差，单位为度；0表示完整几何") double simplifyTolerance) { }

    /** 轮廓批量查询投影；数据库 GeoJSON 文本在业务层转换为 JSON 对象。 */
    @Data
    public static class FootprintRow {
        private Long id;
        private Long dataSetId;
        private Long fileId;
        private String fileName;
        private String layerKey;
        private String extentKind;
        private String geoJson;
    }

    @Schema(description = "创建时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime createTime;

    @Schema(description = "更新时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime updateTime;
}
