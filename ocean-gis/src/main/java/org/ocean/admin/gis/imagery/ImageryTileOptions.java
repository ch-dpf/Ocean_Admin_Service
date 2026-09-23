package org.ocean.admin.gis.imagery;

import org.ocean.admin.gis.dto.GisProcessingParameters;

import java.util.Locale;

/** GeoTIFF 静态影像瓦片的受控生成参数。 */
public record ImageryTileOptions(
        Integer minZoom,
        Integer maxZoom,
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

    public ImageryTileOptions {
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
        return new ImageryTileOptions(null, null, OutputFormat.PNG, Resampling.BILINEAR, true);
    }

    public static ImageryTileOptions from(GisProcessingParameters parameters) {
        if (parameters == null) {
            return defaults();
        }
        String format = parameters.outputFormat().toUpperCase(Locale.ROOT);
        OutputFormat outputFormat = switch (format) {
            case "PNG" -> OutputFormat.PNG;
            case "JPG", "JPEG" -> OutputFormat.JPEG;
            default -> throw new IllegalArgumentException("影像输出格式仅支持 PNG 或 JPEG");
        };
        Resampling resampling = parameters.resampling() == null
                ? Resampling.BILINEAR
                : Resampling.valueOf(parameters.resampling().toUpperCase(Locale.ROOT));
        boolean transparent = parameters.transparent() == null || parameters.transparent();
        return new ImageryTileOptions(parameters.minZoom(), parameters.maxZoom(), outputFormat,
                resampling, transparent);
    }
}
