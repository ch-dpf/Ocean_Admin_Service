package org.ocean.admin.gis.terrain.engine;

import org.ocean.admin.gis.dto.TerrainProcessingParameters;

import java.util.Locale;

/** mago-3d-terrainer 第一阶段支持的稳定参数。 */
public record TerrainOptions(
        int minDepth,
        Integer maxDepth,
        String geoid,
        Double noDataValue,
        InterpolationType interpolationType,
        Integer rasterMaxSize,
        Integer mosaicSize,
        boolean calculateNormals,
        boolean continuePrevious,
        boolean leaveTemp,
        boolean generateLayerJson) {

    public TerrainOptions {
        if (minDepth < 0 || minDepth > 22) {
            throw new IllegalArgumentException("地形最小层级必须在 0 到 22 之间");
        }
        if (maxDepth != null && (maxDepth < 0 || maxDepth > 22)) {
            throw new IllegalArgumentException("地形最大层级必须在 0 到 22 之间");
        }
        if (maxDepth != null && minDepth > maxDepth) {
            throw new IllegalArgumentException("地形最小层级不能大于最大层级");
        }
        if (rasterMaxSize != null && rasterMaxSize < 1) {
            throw new IllegalArgumentException("rasterMaxSize 必须大于 0");
        }
        if (mosaicSize != null && mosaicSize < 1) {
            throw new IllegalArgumentException("mosaicSize 必须大于 0");
        }
        geoid = geoid == null || geoid.isBlank() ? "Ellipsoid" : geoid.trim();
        interpolationType = interpolationType == null
                ? InterpolationType.BILINEAR
                : interpolationType;
    }

    public static TerrainOptions defaults() {
        return new TerrainOptions(
                0,
                null,
                "Ellipsoid",
                null,
                InterpolationType.BILINEAR,
                null,
                null,
                true,
                false,
                false,
                true);
    }

    public static TerrainOptions from(TerrainProcessingParameters parameters) {
        if (parameters == null) {
            return defaults();
        }
        if (!"EPSG:4326".equals(parameters.targetCrs())) {
            throw new IllegalArgumentException("地形目标坐标系仅支持 EPSG:4326");
        }
        if (!"TMS".equals(parameters.tileProfile())) {
            throw new IllegalArgumentException("地形瓦片坐标方案仅支持 TMS");
        }
        if (!"QUANTIZED_MESH".equals(parameters.outputFormat())) {
            throw new IllegalArgumentException("地形输出格式仅支持 QUANTIZED_MESH");
        }
        InterpolationType interpolation;
        try {
            interpolation = parameters.interpolationType() == null
                    ? InterpolationType.BILINEAR
                    : InterpolationType.valueOf(
                            parameters.interpolationType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("地形插值算法仅支持 NEAREST 或 BILINEAR", ex);
        }
        return new TerrainOptions(
                parameters.minZoom() == null ? 0 : parameters.minZoom(),
                parameters.maxZoom(),
                parameters.geoid(),
                parameters.noDataValue(),
                interpolation,
                parameters.rasterMaxSize(),
                parameters.mosaicSize(),
                parameters.calculateNormals() == null || parameters.calculateNormals(),
                Boolean.TRUE.equals(parameters.continuePrevious()),
                Boolean.TRUE.equals(parameters.leaveTemp()),
                parameters.generateLayerJson() == null || parameters.generateLayerJson());
    }

    public enum InterpolationType {
        NEAREST("nearest"),
        BILINEAR("bilinear");

        private final String commandValue;

        InterpolationType(String commandValue) {
            this.commandValue = commandValue;
        }

        public String commandValue() {
            return commandValue;
        }
    }
}
