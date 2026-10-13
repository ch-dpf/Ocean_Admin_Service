package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 地形发布结果。 */
@Data
@Builder
public class GisTerrainPublicationVO {

    private Long id;
    private String serviceCode;
    private Long sourceTaskId;
    private Long dataSetId;

    @Schema(description = "PUBLISHED 或 DISABLED")
    private String status;

    @Schema(description = "CesiumTerrainProvider 使用的服务根 URL")
    private String serviceUrl;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
