package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;
import java.util.List;

/** 地形、影像和矢量瓦片服务共用的发布记录视图。 */
@Data
@Builder
public class GisPublicationVO {
    private Long id;
    private String serviceCode;
    private String serviceName;

    @Schema(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
    private GisProcessingType processingType;

    private Long tileSetId;
    private Long sourceTaskId;

    @Schema(description = "通过任务 ID 查询的当前任务名称，不保存名称快照")
    private String taskName;

    private Long dataSetId;

    @Schema(description = "通过数据集 ID 查询的当前名称；未关联或数据集已删除时为空")
    private String dataSetName;

    @Schema(description = "发布状态：PUBLISHED、DISABLED")
    private String status;

    private String outputKey;
    private String targetCrs;
    private String tileProfile;
    private String outputFormat;
    private Integer minZoom;
    private Integer maxZoom;

    @Schema(description = "服务访问地址：地形为以 / 结尾的根地址，影像为包含 {z}/{x}/{y} 和格式后缀的瓦片模板")
    private String serviceUrl;

    @Schema(description = "服务描述文件地址，影像为 tilejson.json，地形为 layer.json")
    private String descriptorUrl;

    @Schema(description = "瓦片访问模板；不适用的发布类型为空")
    private String tileUrlTemplate;

    @Schema(description = "动态矢量发布的固定数据资源ID")
    private String vectorResourceId;
    private String releaseVersion;
    private String defaultStyleId;
    private tools.jackson.databind.JsonNode styles;

    @Schema(description = "WMTS 能力文档地址；非 WMTS 支持的发布类型为空")
    private String wmtsCapabilitiesUrl;

    @Schema(description = "WMTS 图层标识，取值等同 serviceCode；无对应矩阵集时为空")
    private String wmtsLayer;

    @Schema(description = "WMTS 瓦片矩阵集：WebMercatorQuad 或 WorldCRS84Quad")
    private String wmtsTileMatrixSet;

    @Schema(description = "WMTS 瓦片访问模板，占位 {TileMatrix}/{TileRow}/{TileCol}；仅影像发布有效")
    private String wmtsTileUrlTemplate;

    @Schema(description = "WMTS 各层级有效行列范围，仅影像发布且瓦片集 READY 时返回")
    private List<WmtsMatrixLimitsVO> wmtsTileMatrixSetLimits;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;

    /** 单层级的 WMTS 瓦片行列范围。 */
    @Data
    @Builder
    public static class WmtsMatrixLimitsVO {
        private Integer zoom;
        private Integer minRow;
        private Integer maxRow;
        private Integer minCol;
        private Integer maxCol;
    }
}
