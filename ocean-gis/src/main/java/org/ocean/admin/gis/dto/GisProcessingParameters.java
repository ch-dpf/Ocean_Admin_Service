package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 切片任务的输出参数。 */
public record GisProcessingParameters(
        @NotBlank @Schema(description = "目标坐标系", example = "EPSG:4326") String targetCrs,
        @NotBlank @Schema(description = "瓦片切分剖面", example = "GEODETIC") String tileProfile,
        @NotBlank @Schema(description = "输出格式", example = "QUANTIZED_MESH") String outputFormat) {
}
