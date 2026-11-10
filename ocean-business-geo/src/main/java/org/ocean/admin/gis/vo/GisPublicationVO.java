package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;

/** 地形、影像和矢量瓦片服务共用的发布记录视图。 */
@Data
@Builder
public class GisPublicationVO {
    private Long id;
    private String serviceCode;
    private String serviceName;
    @Schema(description = "明确发布协议：WMS、MVT、TMS_WMTS、XYZ、TMS、TERRAIN")
    private String serviceProtocol;

    @Schema(description = "本次发布支持的协议集合：WMS、WMTS、TMS、WFS、XYZ")
    private java.util.List<String> protocols;

    @Schema(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
    private GisProcessingType processingType;

    private String layerName;
    private String title;
    private String abstractText;
    private String keywords;
    private String defaultCrs;
    private java.util.List<String> supportedCrs;
    private java.util.List<String> tileMatrixSets;
    private java.util.List<Double> boundsWgs84;
    private String styleId;
    private String styleName;
    private java.util.Map<String, String> capabilitiesUrls;
    private java.util.Map<String, String> serviceUrls;
    private java.util.Map<String, String> tileUrlTemplates;

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

    @Schema(description = "服务访问地址：地形根地址、影像瓦片模板、动态矢量根地址或静态配图 WMTS 端点")
    private String serviceUrl;

    @Schema(description = "服务描述地址：影像 TileJSON、地形 layer.json、矢量 manifest 或静态配图 WMTS 能力文档")
    private String descriptorUrl;

    @Schema(description = "瓦片访问模板；不适用的发布类型为空")
    private String tileUrlTemplate;

    @Schema(description = "静态配图 TMS 图层描述地址；同一发布记录同时提供 WMTS")
    private String tmsUrl;
    private String tmsTileUrlTemplate;

    @Schema(description = "动态矢量发布的固定数据资源ID")
    private String vectorResourceId;
    private String vectorDefinition;
    private String releaseVersion;
    private String defaultStyleId;
    private tools.jackson.databind.JsonNode styles;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
