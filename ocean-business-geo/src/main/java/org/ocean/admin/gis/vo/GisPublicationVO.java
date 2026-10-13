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

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
