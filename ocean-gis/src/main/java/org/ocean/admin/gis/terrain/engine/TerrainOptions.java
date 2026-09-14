package org.ocean.admin.gis.terrain.engine;

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
            throw new IllegalArgumentException("minDepth 必须在 0 到 22 之间");
        }
        if (maxDepth != null && (maxDepth < 0 || maxDepth > 22)) {
            throw new IllegalArgumentException("maxDepth 必须在 0 到 22 之间");
        }
        if (maxDepth != null && minDepth > maxDepth) {
            throw new IllegalArgumentException("minDepth 不能大于 maxDepth");
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
