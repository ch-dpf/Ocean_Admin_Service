package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;

/** 任务瓦片服务与数据集 WMS 共用的单协议发布记录视图。 */
@Data
@Builder
public class GisPublicationVO {
    private Long id;
    private String serviceCode;
    private String serviceName;
    @Schema(description = "本记录唯一服务协议：任务 WMTS/TMS 或数据集 WMS")
    private String serviceProtocol;

    @Schema(description = "本记录唯一服务协议：任务 WMTS/TMS 或数据集 WMS")
    private java.util.List<String> protocols;

    @Schema(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
    private GisProcessingType processingType;

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
    private String tileMatrixSet;
    private Integer minZoom;
    private Integer maxZoom;

    @Schema(description = "本服务的单图层能力文档地址")
    private String descriptorUrl;

    @Schema(description = "本服务的协议根地址")
    private String serviceUrl;

    @Schema(description = "本服务的瓦片访问模板")
    private String tileUrlTemplate;

    @Schema(description = "TMS 单服务根文档地址；非 TMS 发布为空")
    private String tmsUrl;
    private String tmsTileUrlTemplate;

    @Schema(description = "数据集 WMS 的发布样式版本，用于更新样式时校验版本")
    private String releaseVersion;
    private String defaultStyleId;
    private tools.jackson.databind.JsonNode styles;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
