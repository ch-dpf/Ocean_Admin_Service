package org.ocean.admin.gis.service;

import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReaderSpi;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFStreamMetadata;
import lombok.RequiredArgsConstructor;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTWriter;
import org.ocean.admin.gis.entity.GisFileSpatialMetadata;
import org.ocean.admin.gis.imagery.ImageryTileOptions;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;
import org.ocean.admin.gis.util.RasterFootprintBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.nio.file.Path;
import java.util.*;

/** 仅使用现有 Java TIFF 解码器提取源参考、米制分辨率及有效像元覆盖区。 */
@Component
@RequiredArgsConstructor
public class RasterSpatialMetadataExtractor {
    private static final double MAX_MERCATOR_LATITUDE = 85.0511287798066;
    private static final double WORLD_METERS = 40075016.68557849;
    private static final long MAX_STRIPE_MASK_BYTES = 16 * 1024 * 1024;
    private static final long MAX_DECODED_TILE_BYTES = 64 * 1024 * 1024;
    private static final GeometryFactory GEOMETRIES = new GeometryFactory(new PrecisionModel(), 4326);
    private final ObjectMapper objectMapper;
    private final GisProcessingProperties properties;

    public void extract(Path path, GisFileSpatialMetadata metadata, boolean terrain, Runnable heartbeat) throws Exception {
        GeoTiffReader reader = new GeoTiffReader(path.toFile());
        try {
            if (reader.getMetadata() == null || !reader.getMetadata().hasGeoKey()) throw failure("MISSING_CRS", "GeoTIFF 缺少源坐标系定义");
            if (reader.getGroundControlPoints() != null) throw failure("UNSUPPORTED_GEOREFERENCING", "暂不支持仅由地面控制点定位的栅格");
            CoordinateReferenceSystem source = reader.getCoordinateReferenceSystem();
            if (source == null) throw failure("MISSING_CRS", "GeoTIFF 缺少源坐标系");
            var grid = reader.getOriginalGridRange();
            if (grid == null || grid.getSpan(0) < 1 || grid.getSpan(1) < 1) throw failure("INVALID_RASTER", "GeoTIFF 栅格尺寸无效");
            int width = grid.getSpan(0), height = grid.getSpan(1);
            double left = grid.getLow(0), top = grid.getLow(1);
            MathTransform gridToSource = reader.getOriginalGridToWorld(PixelInCell.CELL_CORNER);
            CoordinateReferenceSystem geographic = CRS.decode("EPSG:4326", true);
            MathTransform toGeographic = CRS.findMathTransform(source, geographic, false);
            Map<String, Object> extra = new LinkedHashMap<>();
            Double noData = reader.getMetadata().hasNoData() ? reader.getMetadata().getNoData() : null;
            if (noData != null) extra.put("noData", Double.isFinite(noData) ? noData : noData.toString());
            Geometry pixels = readFootprint(path, reader, width, height, noData, heartbeat, extra);
            Geometry footprint = RasterFootprintBuilder.toGeographic(pixels, gridToSource, toGeographic,
                    left, top, width, height, properties.getMetadataMaxCoordinates(), heartbeat);
            metadata.setDataKind("RASTER");
            metadata.setExtentKind("RASTER_FOOTPRINT");
            metadata.setGeographicExtent(new WKTWriter().write(footprint));
            try { metadata.setSourceCrsCode(CRS.lookupIdentifier(source, true)); }
            catch (Exception ignored) { /* 源 CRS 的完整 WKT 仍会保存。 */ }
            metadata.setSourceCrsWkt(source.toWKT());
            metadata.setSourceUnit(source.getCoordinateSystem().getAxis(0).getUnit().toString());
            metadata.setResolutionUnit("m");
            metadata.setRasterWidth(width);
            metadata.setRasterHeight(height);
            metadata.setBandCount(reader.getImageLayout().getSampleModel(null).getNumBands());
            var envelope = reader.getOriginalEnvelope();
            metadata.setSourceExtent(objectMapper.writeValueAsString(Map.of(
                    "lowerCorner", new double[]{envelope.getMinimum(0), envelope.getMinimum(1)},
                    "upperCorner", new double[]{envelope.getMaximum(0), envelope.getMaximum(1)})));
            Resolution resolution = calculateResolution(source, gridToSource, toGeographic, footprint, left, top, width, height);
            metadata.setResolutionX(resolution.x > 0 ? resolution.x : null);
            metadata.setResolutionY(resolution.y > 0 ? resolution.y : null);
            extra.put("resolutionCrs", "EPSG:3857");
            extra.put("resolutionMethod", "MAX_REPRESENTATIVE_PIXEL_SPACING_IN_VALID_MERCATOR_DOMAIN");
            extra.put("resolutionSampleCount", resolution.samples);
            if (resolution.samples == 0) extra.put("resolutionUnavailableReason", "OUTSIDE_WEB_MERCATOR_OR_INVALID_RESOLUTION");
            extra.put("footprintMethod", "VALID_PIXEL_BOUNDARY");
            extra.put("footprintCoordinates", footprint.getNumPoints());
            extra.put("geometrySimplified", false);
            if (terrain) calculateTerrainZoom(metadata, reader, source, width);
            else calculateImageryZoom(metadata, resolution);
            metadata.setExtraMetadata(objectMapper.writeValueAsString(extra));
        } finally {
            reader.dispose();
        }
    }

    private Geometry readFootprint(Path path, GeoTiffReader geoReader, int width, int height,
            Double noData, Runnable heartbeat, Map<String, Object> extra) throws Exception {
        try (var input = new FileImageInputStream(path.toFile())) {
            ImageReader reader = new TIFFImageReaderSpi().createReaderInstance();
            MaskReader mask = null;
            try {
                reader.setInput(input, false, false);
                reader.getNumImages(true);
                var layout = ((TIFFStreamMetadata) reader.getStreamMetadata()).dtLayout;
                if (layout.getNumInternalMasks() > 0) mask = openMask(path, layout.getInternalMaskImageIndex(0), width, height);
                else if (geoReader.getMaskOverviewProvider().hasExternalMasks()) {
                    Path maskPath = Path.of(geoReader.getMaskOverviewProvider().getMaskURL().toURI());
                    mask = openMask(maskPath, 0, width, height);
                }
                var type = reader.getRawImageType(0);
                boolean alpha = type.getColorModel().hasAlpha();
                int dataType = type.getSampleModel().getDataType();
                boolean testSamples = noData != null || dataType == DataBuffer.TYPE_FLOAT || dataType == DataBuffer.TYPE_DOUBLE;
                extra.put("alphaMask", alpha);
                extra.put("separateMask", mask != null);
                extra.put("validityRule", "MASK_AND_ALPHA_AND_ANY_VALID_DATA_BAND");
                if (!alpha && mask == null && !testSamples) {
                    extra.put("validPixelCount", (long) width * height);
                    return new GeometryFactory().toGeometry(new Envelope(0, width, 0, height));
                }
                long bytesPerPixel = Arrays.stream(type.getSampleModel().getSampleSize()).mapToLong(bits -> (bits + 7L) / 8).sum();
                long tileWidth = reader.getTileWidth(0), tileHeight = reader.getTileHeight(0);
                if (tileWidth * tileHeight > MAX_DECODED_TILE_BYTES / Math.max(1, bytesPerPixel)) {
                    throw failure("RASTER_BLOCK_TOO_LARGE", "TIFF 内部块超出安全解码大小");
                }
                long rowBytes = (width + 7L) / 8;
                if (rowBytes > MAX_STRIPE_MASK_BYTES) throw failure("RASTER_TOO_WIDE", "栅格宽度超出轮廓扫描缓冲区限制");
                long maximumPixels = properties.getMetadataMaxWindowPixels();
                int stripeHeight = (int) Math.max(1, Math.min(Math.min(tileHeight, 256),
                        Math.min(MAX_STRIPE_MASK_BYTES / rowBytes, maximumPixels / Math.min(width, tileWidth))));
                int windowWidth = (int) Math.min(width, maximumPixels / stripeHeight);
                if (windowWidth >= tileWidth) windowWidth = (int) (windowWidth / tileWidth * tileWidth);
                RasterFootprintBuilder builder = new RasterFootprintBuilder(width, properties.getMetadataMaxBoundarySegments());
                long validPixels = 0;
                for (int y = 0; y < height; y += stripeHeight) {
                    heartbeat.run();
                    int rows = Math.min(stripeHeight, height - y);
                    BitSet[] stripe = new BitSet[rows];
                    for (int row = 0; row < rows; row++) stripe[row] = new BitSet(width);
                    for (int x = 0; x < width; x += windowWidth) {
                        heartbeat.run();
                        Rectangle region = new Rectangle(x, y, Math.min(windowWidth, width - x), rows);
                        ImageReadParam parameter = reader.getDefaultReadParam();
                        parameter.setSourceRegion(region);
                        BufferedImage image = reader.read(0, parameter);
                        BufferedImage maskImage = mask == null ? null : mask.read(region);
                        validPixels += collectValidPixels(image, maskImage, noData, testSamples, stripe, x);
                    }
                    for (BitSet row : stripe) builder.acceptRow(row);
                }
                extra.put("validPixelCount", validPixels);
                heartbeat.run();
                return builder.buildGeometry();
            } finally {
                if (mask != null) mask.close();
                reader.dispose();
            }
        }
    }

    private long collectValidPixels(BufferedImage image, BufferedImage mask, Double noData,
            boolean testSamples, BitSet[] stripe, int offset) {
        var raster = image.getRaster();
        var alpha = image.getAlphaRaster();
        var maskRaster = mask == null ? null : mask.getRaster();
        int width = image.getWidth(), bands = raster.getNumBands() - (alpha == null ? 0 : 1);
        double[] alphaValues = alpha == null ? null : new double[width];
        double[] maskValues = mask == null ? null : new double[width];
        double[][] samples = testSamples ? new double[bands][width] : null;
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            if (alpha != null) alpha.getSamples(0, y, width, 1, 0, alphaValues);
            if (maskRaster != null) maskRaster.getSamples(0, y, width, 1, 0, maskValues);
            if (testSamples) for (int band = 0; band < bands; band++) raster.getSamples(0, y, width, 1, band, samples[band]);
            for (int x = 0; x < width; x++) {
                if ((alphaValues != null && (!Double.isFinite(alphaValues[x]) || alphaValues[x] <= 0))
                        || (maskValues != null && (!Double.isFinite(maskValues[x]) || maskValues[x] <= 0))) continue;
                boolean valid = !testSamples;
                if (testSamples) for (int band = 0; band < bands; band++) {
                    double value = samples[band][x];
                    if (Double.isFinite(value) && (noData == null || Double.isNaN(noData) || value != noData)) { valid = true; break; }
                }
                if (valid) { stripe[y].set(offset + x); count++; }
            }
        }
        return count;
    }

    private MaskReader openMask(Path path, int index, int width, int height) throws Exception {
        MaskReader mask = new MaskReader(path, index);
        try {
            if (mask.reader.getWidth(index) != width || mask.reader.getHeight(index) != height
                    || mask.reader.getRawImageType(index).getSampleModel().getNumBands() != 1) {
                throw failure("INVALID_MASK", "掩膜必须为与源栅格尺寸一致的单波段图像");
            }
            return mask;
        } catch (Exception error) { mask.close(); throw error; }
    }

    private Resolution calculateResolution(CoordinateReferenceSystem source, MathTransform gridToSource,
            MathTransform toGeographic, Geometry footprint, double left, double top, int width, int height) throws Exception {
        MathTransform toMeters = CRS.findMathTransform(source, CRS.decode("EPSG:3857", true), false);
        Resolution resolution = new Resolution();
        for (double fx : new double[]{0.1, 0.5, 0.9}) for (double fy : new double[]{0.1, 0.5, 0.9}) {
            sampleResolution(resolution, left + fx * width, top + fy * height, gridToSource, toGeographic, toMeters);
        }
        // 极区边缘可能完全避开九个采样点，使用与有效域相交部分的内部点补充采样。
        if (resolution.samples < 9) {
            Geometry domain = footprint.intersection(GEOMETRIES.toGeometry(new Envelope(-180, 180,
                    -MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)));
            MathTransform toSource = toGeographic.inverse(), toGrid = gridToSource.inverse();
            for (int i = 0; i < domain.getNumGeometries(); i++) {
                Geometry part = domain.getGeometryN(i);
                if (part.isEmpty() || part.getDimension() != 2) continue;
                Coordinate point = part.getInteriorPoint().getCoordinate();
                for (int offset : new int[]{0, -1, 1}) {
                    double[] pixel;
                    try {
                        double[] p = transform(toSource, new double[]{point.x + offset * 360d, point.y});
                        pixel = transform(toGrid, p);
                    } catch (org.geotools.api.referencing.operation.TransformException outsideProjection) {
                        continue;
                    }
                    if (pixel[0] >= left && pixel[0] <= left + width && pixel[1] >= top && pixel[1] <= top + height) {
                        int previousSamples = resolution.samples;
                        sampleResolution(resolution, pixel[0], pixel[1], gridToSource, toGeographic, toMeters);
                        if (resolution.samples > previousSamples) break;
                    }
                }
            }
        }
        return resolution;
    }

    private void sampleResolution(Resolution result, double x, double y, MathTransform gridToSource,
            MathTransform toGeographic, MathTransform toMeters) throws Exception {
        double[] source = transform(gridToSource, new double[]{x, y});
        if (!insideMercator(transform(toGeographic, source))) return;
        double rx = axisSpacing(x, y, true, gridToSource, toGeographic, toMeters);
        double ry = axisSpacing(x, y, false, gridToSource, toGeographic, toMeters);
        if (Double.isFinite(rx) && rx > 0 && Double.isFinite(ry) && ry > 0) {
            result.x = Math.max(result.x, rx);
            result.y = Math.max(result.y, ry);
            result.samples++;
        }
    }

    private double axisSpacing(double x, double y, boolean horizontal, MathTransform gridToSource,
            MathTransform toGeographic, MathTransform toMeters) throws Exception {
        for (int attempt = 0; attempt < 32; attempt++) {
            double step = Math.scalb(attempt % 2 == 0 ? 1d : -1d, -(attempt / 2));
            double[] previous = transform(toMeters, transform(gridToSource, new double[]{x, y}));
            double dx = 0, dy = 0;
            boolean valid = true;
            for (int sample = 1; sample <= 4; sample++) {
                double offset = step * sample / 4;
                double[] source = transform(gridToSource, new double[]{x + (horizontal ? offset : 0), y + (horizontal ? 0 : offset)});
                if (!insideMercator(transform(toGeographic, source))) { valid = false; break; }
                double[] current = transform(toMeters, source);
                double deltaX = current[0] - previous[0];
                deltaX -= Math.rint(deltaX / WORLD_METERS) * WORLD_METERS;
                dx += deltaX;
                dy += current[1] - previous[1];
                previous = current;
            }
            if (valid) return Math.hypot(dx, dy) / Math.abs(step);
        }
        return Double.NaN;
    }

    private static boolean insideMercator(double[] point) {
        return Double.isFinite(point[0]) && Double.isFinite(point[1]) && Math.abs(point[1]) <= MAX_MERCATOR_LATITUDE;
    }

    private void calculateImageryZoom(GisFileSpatialMetadata metadata, Resolution resolution) {
        double spacing = Math.max(resolution.x, resolution.y);
        if (resolution.samples == 0 || !Double.isFinite(spacing) || spacing <= 0) {
            metadata.setCalculationDetails(objectMapper.writeValueAsString(Map.of("reason", "OUTSIDE_WEB_MERCATOR_OR_INVALID_RESOLUTION")));
            return;
        }
        int nativeZoom = ImageryTileOptions.calculateNativeMaxZoom(spacing, WORLD_METERS, 256);
        metadata.setTileMatrixSetId("WebMercatorQuad");
        metadata.setTileWidth(256);
        metadata.setTileHeight(256);
        metadata.setRecommendedMaxZoom(Math.clamp(nativeZoom, 0, 22));
        metadata.setCalculationDetails(objectMapper.writeValueAsString(Map.of(
                "method", "MAX_REPRESENTATIVE_PIXEL_SPACING_IN_EPSG_3857", "resolutionMeters", spacing,
                "unconstrainedMaxZoom", nativeZoom, "rounding", "FLOOR_WITH_INTEGER_TOLERANCE",
                "zoomBoundaryTolerance", ImageryTileOptions.ZOOM_BOUNDARY_TOLERANCE, "systemMaxZoom", 22)));
    }

    private void calculateTerrainZoom(GisFileSpatialMetadata metadata, GeoTiffReader reader,
            CoordinateReferenceSystem source, int width) {
        var bounds = reader.getOriginalEnvelope();
        double pixelSizeMeters;
        try {
            if (CRS.equalsIgnoreMetadata(source, CRS.decode("EPSG:4326", true))) {
                double latitude = (bounds.getMinimum(1) + bounds.getMaximum(1)) / 2;
                double sin = Math.sin(Math.toRadians(latitude));
                double radius = 6378137d / Math.sqrt(1 - 0.0066943799901413165d * sin * sin);
                pixelSizeMeters = Math.toRadians(bounds.getSpan(0)) * radius / width;
            } else {
                String unit = source.getCoordinateSystem().getAxis(0).getUnit().toString();
                if (!Set.of("m", "metre", "meter").contains(unit)) {
                    metadata.setCalculationDetails(objectMapper.writeValueAsString(Map.of("reason", "TERRAIN_SOURCE_UNIT_NOT_SUPPORTED")));
                    return;
                }
                pixelSizeMeters = bounds.getSpan(0) / width;
            }
        } catch (org.geotools.api.referencing.FactoryException error) { throw new IllegalStateException(error); }
        metadata.setTileMatrixSetId("WorldCRS84Quad");
        metadata.setTileWidth(256);
        metadata.setTileHeight(256);
        metadata.setRecommendedMaxZoom(TerrainOptions.calculateRecommendedMaxZoom(pixelSizeMeters));
        metadata.setCalculationDetails(objectMapper.writeValueAsString(Map.of(
                "method", "MAGO_DEFAULT_AVAILABLE_DEPTH", "engine", "mago-3d-terrainer-1.14.2-release",
                "resolutionMeters", pixelSizeMeters, "includesFinalSubdivision", true, "systemMaxZoom", 22)));
    }

    private static double[] transform(MathTransform transform, double[] point) throws Exception {
        double[] result = new double[2];
        transform.transform(point, 0, result, 0, 1);
        return result;
    }

    private static final class Resolution { private double x, y; private int samples; }

    private static final class MaskReader implements AutoCloseable {
        private final FileImageInputStream input;
        private final ImageReader reader;
        private final int index;
        private MaskReader(Path path, int index) throws Exception {
            this.index = index;
            input = new FileImageInputStream(path.toFile());
            reader = new TIFFImageReaderSpi().createReaderInstance();
            reader.setInput(input, false, false);
        }
        private BufferedImage read(Rectangle region) throws Exception {
            ImageReadParam parameter = reader.getDefaultReadParam();
            parameter.setSourceRegion(region);
            return reader.read(index, parameter);
        }
        @Override public void close() throws Exception { try { reader.dispose(); } finally { input.close(); } }
    }

    private static ExtractionException failure(String code, String message) { return new ExtractionException(code, message); }
    public static final class ExtractionException extends IllegalArgumentException {
        private final String code;
        private ExtractionException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
