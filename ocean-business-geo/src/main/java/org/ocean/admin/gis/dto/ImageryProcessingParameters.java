package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.util.Locale;

/** GeoTIFF 静态影像切片参数。 */
public record ImageryProcessingParameters(
        @Schema(description = "目标坐标系，默认 EPSG:3857",
                allowableValues = {"EPSG:3857", "EPSG:4326"}, defaultValue = "EPSG:3857")
        String targetCrs,
        @Schema(description = "瓦片坐标方案，默认 XYZ", allowableValues = {"XYZ", "TMS"},
                defaultValue = "XYZ")
        String tileProfile,
        @Schema(description = "输出格式，默认 PNG", allowableValues = {"PNG", "JPEG", "JPG"},
                defaultValue = "PNG")
        String outputFormat,
        @Min(0) @Max(22) @Schema(description = "最小层级；为空时根据影像范围自动计算")
        Integer minZoom,
        @Min(0) @Max(22) @Schema(description = "最大层级；为空时按源分辨率计算")
        Integer maxZoom,
        @Schema(description = "重采样算法，默认 BILINEAR",
                allowableValues = {"NEAREST", "BILINEAR"}, defaultValue = "BILINEAR")
        String resampling,
        @Schema(description = "PNG 是否保留透明背景，默认 true", defaultValue = "true")
        Boolean transparent)
        implements GisProcessingParameters {

    public ImageryProcessingParameters {
        targetCrs = normalize(targetCrs);
        tileProfile = normalize(tileProfile);
        outputFormat = normalize(outputFormat);
        resampling = normalize(resampling);
        targetCrs = targetCrs == null ? "EPSG:3857" : targetCrs;
        tileProfile = tileProfile == null ? "XYZ" : tileProfile;
        outputFormat = outputFormat == null ? "PNG" : outputFormat;
        resampling = resampling == null ? "BILINEAR" : resampling;
        transparent = "JPEG".equals(outputFormat) || "JPG".equals(outputFormat)
                ? false : transparent == null || transparent;
    }

    @Override
    public GisProcessingType processingType() {
        return GisProcessingType.IMAGERY;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

}
