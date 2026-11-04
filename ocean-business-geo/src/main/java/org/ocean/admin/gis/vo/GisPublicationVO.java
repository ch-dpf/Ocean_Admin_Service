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

    @Schema(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
    private GisProcessingType processingType;

    private Long tileSetId;
    private Long sourceTaskId;
    private Long dataSetId;

    @Schema(description = "发布状态：PUBLISHED、DISABLED")
    private String status;

    private String outputKey;
    private String targetCrs;
    private String tileProfile;
    private String outputFormat;
    private Integer minZoom;
    private Integer maxZoom;

    @Schema(description = "已发布服务的访问根地址")
    private String serviceUrl;

    @Schema(description = "服务描述文件地址，影像为 tilejson.json，地形为 layer.json")
    private String descriptorUrl;

    @Schema(description = "瓦片访问模板；不适用的发布类型为空")
    private String tileUrlTemplate;

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
