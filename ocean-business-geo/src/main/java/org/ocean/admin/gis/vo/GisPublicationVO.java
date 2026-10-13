package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 地形、影像和矢量瓦片服务共用的发布记录视图。 */
@Data
@Builder
public class GisPublicationVO {
    private Long id;
    private String serviceCode;

    @Schema(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
    private String processingType;

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

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
