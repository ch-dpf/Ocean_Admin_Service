package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.ogc.OgcProtocol;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/** 栅格独立发布服务与动态矢量 OGC 服务共用的发布结果视图。 */
@Data
@Builder
public class GisOgcServiceVO {

    private Long id;
    private String serviceCode;

    @Schema(description = "发布资源类型：RASTER、VECTOR")
    private String resourceType;

    @Schema(description = "独立栅格服务协议：WMTS、WMS、TMS；动态矢量服务为空")
    private OgcProtocol protocol;

    private String serviceName;

    @Schema(description = "图层展示名称；动态矢量服务与 serviceName 相同")
    private String layerName;
    private String description;

    @Schema(description = "发布状态：PUBLISHED、DISABLED")
    private String status;

    @Schema(description = "产物可用性：栅格服务返回派生状态；动态矢量服务返回 AVAILABLE")
    private String availability;

    private Long sourceTaskId;
    private String taskName;
    private Long tileSetId;
    private Long dataSetId;
    private String dataSetName;

    private Integer minZoom;
    private Integer maxZoom;
    private String targetCrs;
    private String tileMatrixSet;
    private String outputFormat;
    private String tileProfile;

    @Schema(description = "服务根地址")
    private String serviceUrl;

    @Schema(description = "能力文档地址；动态矢量服务指向 WMS capabilities")
    private String capabilitiesUrl;

    @Schema(description = "瓦片访问模板；动态矢量服务指向 WMTS RESTful 模板")
    private String tileUrlTemplate;

    @Schema(description = "动态矢量 OGC 能力文档地址")
    private String wmsCapabilitiesUrl;
    private String wmtsCapabilitiesUrl;
    private String tmsCapabilitiesUrl;
    private String wfsCapabilitiesUrl;

    @Schema(description = "动态矢量发布绑定的固定数据资源 ID")
    private String vectorResourceId;
    private String releaseVersion;
    private String defaultStyleId;

    @Schema(description = "动态矢量发布包含的样式方案")
    private JsonNode styles;

    @Schema(description = "WMTS 各层级有效行列范围；仅独立栅格 WMTS 返回")
    private List<WmtsMatrixLimitsVO> wmtsTileMatrixSetLimits;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;

    /** 单层级 WMTS 瓦片行列范围。 */
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
