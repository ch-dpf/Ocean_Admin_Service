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

    @Schema(description = "数据集分类统计汇总")
    public record CategorySummary(
            @Schema(description = "全部未删除数据集数量") long totalCount,
            @Schema(description = "按影像、地形、矢量顺序返回的分类统计") List<CategoryStatistics> categories) { }

    @Schema(description = "单个分类的数据集统计")
    public record CategoryStatistics(
            @Schema(description = "分类编码：0-影像、1-地形、2-矢量") Long categoryId,
            @Schema(description = "分类名称") String categoryName,
            @Schema(description = "该分类下未删除数据集数量") long dataSetCount) { }

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

    @Schema(description = "数据集描述")
    private String description;

    @Schema(description = "数据集编码", accessMode = Schema.AccessMode.READ_ONLY)
    private String dataSetCode;

    @Schema(description = "未删除且源状态为READY的管理文件数量，包含工作区引用，与总大小同口径", accessMode = Schema.AccessMode.READ_ONLY)
    private Integer fileCount;
    @Schema(description = "有效逻辑文件组数量，不含重复输入追溯组", accessMode = Schema.AccessMode.READ_ONLY)
    private long fileGroupCount;

    @Schema(description = "未删除且源状态为READY的管理文件总大小（字节），包含工作区引用及配套文件，与元数据提取状态无关",
            accessMode = Schema.AccessMode.READ_ONLY)
    private Long totalSizeBytes;

    @Schema(description = "源状态为READY的未删除文件及其轮廓处理状态摘要；分页接口不加载", accessMode = Schema.AccessMode.READ_ONLY)
    private FileSummary fileSummary;

    @Schema(description = "EPSG:4326 GeoJSON FeatureCollection；可用范围合并为一个数据集级要素，无范围时为空；仅轮廓接口返回",
            accessMode = Schema.AccessMode.READ_ONLY)
    private FootprintCollection footprints;

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

    public record FootprintProperties(Long dataSetId, long sourceFileCount, long sourceLayerCount,
            @Schema(description = "来源范围类型：RASTER_FOOTPRINT、VECTOR_ENVELOPE 或 MIXED") String extentKind,
            @Schema(description = "是否应用预览简化与精度对齐；小于预览精度的孔洞或分块可能消失") boolean simplified,
            @Schema(description = "预览简化容差及对齐网格大小，单位为度；0表示完整精度合并") double simplifyTolerance) { }

    /** 轮廓批量查询投影；数据库 GeoJSON 文本在业务层转换为 JSON 对象。 */
    @Data
    public static class FootprintRow {
        private Long dataSetId;
        private long sourceFileCount;
        private long sourceLayerCount;
        private String extentKind;
        private String geoJson;
    }

    @Schema(description = "创建时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime createTime;

    @Schema(description = "更新时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime updateTime;
}
