package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 切片任务的输出参数。 */
public record GisProcessingParameters(
        @NotBlank @Schema(description = "目标坐标系", example = "EPSG:4326") String targetCrs,
        @NotBlank @Schema(description = "瓦片切分剖面", example = "GEODETIC") String tileProfile,
        @NotBlank @Schema(description = "输出格式", example = "QUANTIZED_MESH") String outputFormat,
        @Min(0) @Max(22) @Schema(description = "影像最小层级；为空时自动计算") Integer minZoom,
        @Min(0) @Max(22) @Schema(description = "影像最大层级；为空时按源分辨率计算") Integer maxZoom,
        @Schema(description = "影像重采样算法", allowableValues = {"NEAREST", "BILINEAR"})
        String resampling,
        @Schema(description = "PNG 是否保留透明背景") Boolean transparent) {
}
