package org.ocean.admin.gis.imagery;

import org.ocean.admin.gis.dto.ImageryProcessingParameters;

import java.util.Locale;

/** GeoTIFF 静态影像瓦片的受控生成参数。 */
public record ImageryTileOptions(
        Integer minZoom,
        Integer maxZoom,
        TargetCrs targetCrs,
        TileProfile tileProfile,
        OutputFormat outputFormat,
        Resampling resampling,
        boolean transparent) {

    public static final int TILE_SIZE = 256;
    public static final int MAX_ZOOM = 22;
    public static final long MAX_TILE_COUNT = 100_000;

    public enum OutputFormat {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg");

        private final String extension;
        private final String mediaType;

        OutputFormat(String extension, String mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }

        public String extension() {
            return extension;
        }

        public String mediaType() {
            return mediaType;
        }
    }

    public enum Resampling {
        NEAREST,
        BILINEAR
    }

    public enum TargetCrs {
        EPSG_3857("EPSG:3857", "WebMercatorQuad", 1),
        EPSG_4326("EPSG:4326", "WorldCRS84Quad", 2);

        private final String code;
        private final String tileMatrixSet;
        private final int levelZeroWidth;

        TargetCrs(String code, String tileMatrixSet, int levelZeroWidth) {
            this.code = code;
            this.tileMatrixSet = tileMatrixSet;
            this.levelZeroWidth = levelZeroWidth;
        }

        public String code() {
            return code;
        }

        public String tileMatrixSet() {
            return tileMatrixSet;
        }

        public int matrixWidth(int zoom) {
            return levelZeroWidth << zoom;
        }

        public int matrixHeight(int zoom) {
            return 1 << zoom;
        }

        public static TargetCrs fromCode(String value) {
            String code = value == null
                    ? "EPSG:3857" : value.trim().toUpperCase(Locale.ROOT);
            return switch (code) {
                case "EPSG:3857" -> EPSG_3857;
                case "EPSG:4326" -> EPSG_4326;
                default -> throw new IllegalArgumentException(
                        "影像目标坐标系仅支持 EPSG:3857 或 EPSG:4326");
            };
        }
    }

    public enum TileProfile {
        XYZ("xyz"),
        TMS("tms");

        private final String scheme;

        TileProfile(String scheme) {
            this.scheme = scheme;
        }

        public String scheme() {
            return scheme;
        }

        public int storedY(int matrixHeight, int xyzY) {
            return this == XYZ ? xyzY : matrixHeight - 1 - xyzY;
        }

        public String manifestType() {
            return "IMAGERY_" + name();
        }
    }

    public ImageryTileOptions {
        targetCrs = targetCrs == null ? TargetCrs.EPSG_3857 : targetCrs;
        tileProfile = tileProfile == null ? TileProfile.XYZ : tileProfile;
        outputFormat = outputFormat == null ? OutputFormat.PNG : outputFormat;
        resampling = resampling == null ? Resampling.BILINEAR : resampling;
        if (minZoom != null && (minZoom < 0 || minZoom > MAX_ZOOM)) {
            throw new IllegalArgumentException("影像最小层级必须在0到22之间");
        }
        if (maxZoom != null && (maxZoom < 0 || maxZoom > MAX_ZOOM)) {
            throw new IllegalArgumentException("影像最大层级必须在0到22之间");
        }
        if (minZoom != null && maxZoom != null && minZoom > maxZoom) {
            throw new IllegalArgumentException("影像最小层级不能大于最大层级");
        }
        if (outputFormat == OutputFormat.JPEG) {
            transparent = false;
        }
    }

    public static ImageryTileOptions defaults() {
        return new ImageryTileOptions(
                null, null, TargetCrs.EPSG_3857, TileProfile.XYZ,
                OutputFormat.PNG, Resampling.BILINEAR, true);
    }

    public static ImageryTileOptions from(ImageryProcessingParameters parameters) {
        if (parameters == null) {
            return defaults();
        }
        TargetCrs targetCrs = TargetCrs.fromCode(parameters.targetCrs());
        String format = parameters.outputFormat() == null
                ? "PNG" : parameters.outputFormat().toUpperCase(Locale.ROOT);
        OutputFormat outputFormat = switch (format) {
            case "PNG" -> OutputFormat.PNG;
            case "JPG", "JPEG" -> OutputFormat.JPEG;
            default -> throw new IllegalArgumentException("影像输出格式仅支持 PNG 或 JPEG");
        };
        Resampling resampling;
        try {
            resampling = parameters.resampling() == null
                    ? Resampling.BILINEAR
                    : Resampling.valueOf(parameters.resampling().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("影像重采样仅支持 NEAREST 或 BILINEAR", ex);
        }
        TileProfile tileProfile;
        try {
            tileProfile = parameters.tileProfile() == null
                    ? TileProfile.XYZ
                    : TileProfile.valueOf(parameters.tileProfile().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("影像瓦片坐标方案仅支持 XYZ 或 TMS", ex);
        }
        boolean transparent = parameters.transparent() == null || parameters.transparent();
        return new ImageryTileOptions(parameters.minZoom(), parameters.maxZoom(), targetCrs,
                tileProfile, outputFormat, resampling, transparent);
    }
}
