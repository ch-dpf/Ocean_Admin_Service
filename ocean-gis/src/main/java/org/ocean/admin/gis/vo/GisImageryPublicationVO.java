package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** XYZ 影像瓦片发布结果。 */
@Data
@Builder
public class GisImageryPublicationVO {

    private Long id;
    private String serviceCode;
    private Long sourceTaskId;
    private Long dataSetId;

    @Schema(description = "PUBLISHED 或 DISABLED")
    private String status;

    private String targetCrs;
    private String tileProfile;
    private String outputFormat;
    private Integer minZoom;
    private Integer maxZoom;

    @Schema(description = "影像服务根 URL")
    private String serviceUrl;

    @Schema(description = "TileJSON 地址")
    private String tileJsonUrl;

    @Schema(description = "XYZ 瓦片 URL 模板")
    private String tileUrlTemplate;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
