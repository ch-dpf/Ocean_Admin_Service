package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.util.Locale;

/** Quantized Mesh 地形切片参数。 */
public record TerrainProcessingParameters(
        @Schema(description = "目标坐标系，固定 EPSG:4326",
                allowableValues = {"EPSG:4326"}, defaultValue = "EPSG:4326")
        String targetCrs,
        @Schema(description = "瓦片坐标方案，固定 TMS",
                allowableValues = {"TMS"}, defaultValue = "TMS")
        String tileProfile,
        @Schema(description = "输出格式，固定 QUANTIZED_MESH",
                allowableValues = {"QUANTIZED_MESH"}, defaultValue = "QUANTIZED_MESH")
        String outputFormat,
        @Min(0) @Max(22)
        @Schema(description = "最小地形层级，默认 0", example = "0", defaultValue = "0")
        Integer minZoom,
        @Min(0) @Max(22) @Schema(description = "最大地形层级") Integer maxZoom,
        @Schema(description = "高程基准，默认 Ellipsoid", defaultValue = "Ellipsoid")
        String geoid,
        @Schema(description = "无数据值") Double noDataValue,
        @Schema(description = "插值算法，默认 BILINEAR",
                allowableValues = {"NEAREST", "BILINEAR"}, defaultValue = "BILINEAR")
        String interpolationType,
        @Positive @Schema(description = "单个栅格最大尺寸") Integer rasterMaxSize,
        @Positive @Schema(description = "镶嵌尺寸") Integer mosaicSize,
        @Schema(description = "是否计算法线，默认 true") Boolean calculateNormals,
        @Schema(description = "是否继续上一次处理，默认 false") Boolean continuePrevious,
        @Schema(description = "是否保留临时文件，默认 false") Boolean leaveTemp,
        @Schema(description = "是否生成 layer.json，默认 true") Boolean generateLayerJson)
        implements GisProcessingParameters {

    public TerrainProcessingParameters {
        targetCrs = normalize(targetCrs);
        tileProfile = normalize(tileProfile);
        outputFormat = normalize(outputFormat);
        targetCrs = targetCrs == null ? "EPSG:4326" : targetCrs;
        tileProfile = tileProfile == null || "GEODETIC".equals(tileProfile)
                ? "TMS" : tileProfile;
        outputFormat = outputFormat == null ? "QUANTIZED_MESH" : outputFormat;
        minZoom = minZoom == null ? 0 : minZoom;
        geoid = geoid == null || geoid.isBlank() ? "Ellipsoid" : geoid.trim();
        interpolationType = interpolationType == null ? "BILINEAR" : interpolationType;
        calculateNormals = calculateNormals == null || calculateNormals;
        continuePrevious = Boolean.TRUE.equals(continuePrevious);
        leaveTemp = Boolean.TRUE.equals(leaveTemp);
        generateLayerJson = generateLayerJson == null || generateLayerJson;
    }

    public static TerrainProcessingParameters defaults() {
        return new TerrainProcessingParameters(
                "EPSG:4326", "TMS", "QUANTIZED_MESH",
                0, null, "Ellipsoid", null, "BILINEAR", null, null,
                true, false, false, true);
    }

    @Override
    public GisProcessingType processingType() {
        return GisProcessingType.TERRAIN;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toUpperCase(Locale.ROOT);
    }

}
