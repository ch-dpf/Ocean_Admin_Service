package org.ocean.admin.gis.imagery;

import org.eclipse.imagen.ImageLayout;
import org.eclipse.imagen.Interpolation;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.OverviewPolicy;
import org.geotools.coverage.grid.io.imageio.GeoToolsWriteParams;
import org.geotools.coverage.processing.Operations;
import org.geotools.coverage.util.CoverageUtilities;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.ocean.admin.gis.processing.GisProcessingProgress;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;

import javax.imageio.ImageWriteParam;
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/** 为大 GeoTIFF 构建可复用的目标 CRS 分块影像及外部 overview。 */
public class GeoTiffPreprocessor {

    private static final String CACHE_VERSION = "v1";
    private static final int TIFF_TILE_SIZE = 256;
    private static final int MAX_ZOOM = ImageryTileOptions.MAX_ZOOM;
    private static final double WEB_MERCATOR_LIMIT = 20_037_508.342789244;
    private static final double WEB_MERCATOR_MAX_LATITUDE = 85.0511287798066;
    private static final long BIG_TIFF_THRESHOLD = 3_500_000_000L;
    private static final CoordinateReferenceSystem WGS84 = decode("EPSG:4326");
    private static final CoordinateReferenceSystem WEB_MERCATOR = decode("EPSG:3857");

    private final Path cacheRoot;
    private final boolean enabled;
    private final long minimumFileSize;
    private final long minimumPixels;
    private final int overviewMinimumSize;
    private final GeoTiffOverviewBuilder overviewBuilder;

    public GeoTiffPreprocessor(GisProcessingProperties properties,
            GeoTiffOverviewBuilder overviewBuilder) {
        Path processingRoot = Path.of(properties.getBasePath()).toAbsolutePath().normalize();
        Path configuredCache = Path.of(properties.getImageryCachePath());
        this.cacheRoot = (configuredCache.isAbsolute()
                ? configuredCache : processingRoot.resolve(configuredCache)).normalize();
        this.enabled = properties.isImageryOptimizationEnabled();
        this.minimumFileSize = properties.getImageryOptimizationMinFileSize();
        this.minimumPixels = properties.getImageryOptimizationMinPixels();
        this.overviewMinimumSize = properties.getImageryOverviewMinSize();
        this.overviewBuilder = overviewBuilder;
    }

    public PreparedRaster prepare(Path input, ImageryTileOptions options,
            Consumer<GisProcessingProgress> progressListener) {
        Path source = input.toAbsolutePath().normalize();
        GeoTiffReader reader = null;
        try {
            reader = new GeoTiffReader(source.toFile());
            RasterInspection inspection = inspect(source, reader, options);
            if (!shouldPrepare(inspection)) {
                return new PreparedRaster(source, false, false);
            }

            Path cacheDirectory = cacheRoot.resolve(cacheKey(source, inspection, options));
            Files.createDirectories(cacheDirectory);
            Path optimized = cacheDirectory.resolve("optimized.tif");
            Path lockPath = cacheDirectory.resolve("preparation.lock");
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lockChannel.lock()) {
                if (isValidCache(optimized)) {
                    progressListener.accept(GisProcessingProgress.indeterminate(
                            "preparing", "已复用影像优化缓存"));
                    return new PreparedRaster(optimized, true, true);
                }
                Files.deleteIfExists(overviewBuilder.overviewPath(optimized));
                Files.deleteIfExists(optimized);
                progressListener.accept(GisProcessingProgress.indeterminate(
                        "preparing", inspection.reprojectionRequired()
                                ? "正在构建目标坐标系分块影像"
                                : "正在构建高效分块影像"));
                writeOptimized(reader, inspection, options, optimized);
                overviewBuilder.build(optimized, overviewMinimumSize, progressListener);
                overviewBuilder.validate(optimized, overviewMinimumSize);
                return new PreparedRaster(optimized, true, false);
            }
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
                throw (RuntimeException) ex;
            }
            throw new IllegalStateException("GeoTIFF 预处理失败: " + ex.getMessage(), ex);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private RasterInspection inspect(Path source, GeoTiffReader reader,
            ImageryTileOptions options) throws Exception {
        GridEnvelope range = reader.getOriginalGridRange();
        int width = range.getSpan(0);
        int height = range.getSpan(1);
        long pixels = Math.multiplyExact((long) width, height);
        CoordinateReferenceSystem sourceCrs = canonicalCrs(reader.getCoordinateReferenceSystem());
        CoordinateReferenceSystem targetCrs = targetCrs(options.targetCrs());
        boolean reprojectionRequired = !CRS.equalsIgnoreMetadata(sourceCrs, targetCrs);
        ImageLayout layout = reader.getImageLayout();
        int tileWidth = layout.isValid(ImageLayout.TILE_WIDTH_MASK)
                ? layout.getTileWidth(null) : width;
        int tileHeight = layout.isValid(ImageLayout.TILE_HEIGHT_MASK)
                ? layout.getTileHeight(null) : height;
        boolean efficientTiles = tileWidth > 0 && tileHeight > 0
                && tileWidth <= 1024 && tileHeight <= 1024
                && tileWidth < width && tileHeight < height;
        double[][] resolutions = reader.getResolutionLevels();
        boolean hasOverviews = resolutions != null && resolutions.length > 1;
        SampleModel sampleModel = layout.getSampleModel(null);
        int bands = sampleModel == null ? 4 : sampleModel.getNumBands();
        ReferencedEnvelope sourceBounds = new ReferencedEnvelope(reader.getOriginalEnvelope())
                .transform(sourceCrs, true);
        return new RasterInspection(Files.size(source), pixels, width, height, bands,
                sourceCrs, targetCrs, sourceBounds, efficientTiles, hasOverviews,
                reprojectionRequired);
    }

    private boolean shouldPrepare(RasterInspection inspection) {
        if (!enabled) {
            return false;
        }
        boolean large = inspection.fileSize() >= minimumFileSize
                || inspection.pixelCount() >= minimumPixels;
        return large && (inspection.reprojectionRequired()
                || !inspection.efficientTiles() || !inspection.hasOverviews());
    }

    private void writeOptimized(GeoTiffReader reader, RasterInspection inspection,
            ImageryTileOptions options, Path optimized) throws Exception {
        Path temporary = optimized.resolveSibling(optimized.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        GridCoverage2D sourceCoverage = null;
        GridCoverage2D preparedCoverage = null;
        GeoTiffWriter writer = null;
        try {
            sourceCoverage = reader.read(baseReadParameters());
            if (sourceCoverage == null) {
                throw new IOException("GeoTIFF 原始分辨率读取结果为空");
            }
            if (inspection.reprojectionRequired()) {
                GridGeometry2D targetGrid = targetGrid(inspection, options);
                preparedCoverage = (GridCoverage2D) Operations.DEFAULT.resample(
                        sourceCoverage, inspection.targetCrs(), targetGrid,
                        interpolation(options.resampling()),
                        backgroundValues(sourceCoverage, inspection.bands()));
            } else {
                preparedCoverage = sourceCoverage;
            }

            GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
            writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParams.setCompressionType("Deflate");
            writeParams.setCompressionQuality(0.6f);
            writeParams.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
            writeParams.setTiling(TIFF_TILE_SIZE, TIFF_TILE_SIZE);
            writeParams.setForceToBigTIFF(estimatedUncompressedSize(preparedCoverage) >= BIG_TIFF_THRESHOLD);
            ParameterValue<GeoToolsWriteParams> parameters =
                    AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.createValue();
            parameters.setValue(writeParams);

            writer = new GeoTiffWriter(temporary.toFile());
            writer.write(preparedCoverage, parameters);
            writer.dispose();
            writer = null;
            moveAtomically(temporary, optimized);
        } catch (Exception ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        } finally {
            if (writer != null) {
                writer.dispose();
            }
            if (preparedCoverage != null && preparedCoverage != sourceCoverage) {
                preparedCoverage.dispose(true);
            }
            if (sourceCoverage != null) {
                sourceCoverage.dispose(true);
            }
        }
    }

    private GridGeometry2D targetGrid(RasterInspection inspection,
            ImageryTileOptions options) throws Exception {
        ReferencedEnvelope sourceBounds = validSourceBounds(
                inspection.sourceBounds(), inspection.sourceCrs(), inspection.targetCrs());
        ReferencedEnvelope transformed = sourceBounds.transform(inspection.targetCrs(), true);
        ReferencedEnvelope bounded = clampTarget(transformed, options.targetCrs());
        double sourceResolution = Math.max(
                bounded.getWidth() / inspection.width(), bounded.getHeight() / inspection.height());
        int nativeZoom = (int) Math.floor(Math.log(worldHeight(options.targetCrs())
                / (ImageryTileOptions.TILE_SIZE * sourceResolution)) / Math.log(2));
        nativeZoom = Math.max(0, Math.min(MAX_ZOOM, nativeZoom));
        int preparationZoom = options.maxZoom() == null
                ? nativeZoom : Math.min(nativeZoom, options.maxZoom());
        double resolution = worldHeight(options.targetCrs())
                / (ImageryTileOptions.TILE_SIZE * (1L << preparationZoom));
        ReferencedEnvelope aligned = alignToPixelGrid(bounded, options.targetCrs(), resolution);
        int width = Math.toIntExact(Math.round(aligned.getWidth() / resolution));
        int height = Math.toIntExact(Math.round(aligned.getHeight() / resolution));
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("重投影后的目标栅格尺寸无效");
        }
        return new GridGeometry2D(new GridEnvelope2D(0, 0, width, height), aligned);
    }

    private ReferencedEnvelope validSourceBounds(ReferencedEnvelope bounds,
            CoordinateReferenceSystem sourceCrs, CoordinateReferenceSystem targetCrs) {
        if (!CRS.equalsIgnoreMetadata(sourceCrs, WGS84)
                || !CRS.equalsIgnoreMetadata(targetCrs, WEB_MERCATOR)) {
            return bounds;
        }
        double minY = Math.max(bounds.getMinY(), -WEB_MERCATOR_MAX_LATITUDE);
        double maxY = Math.min(bounds.getMaxY(), WEB_MERCATOR_MAX_LATITUDE);
        if (minY >= maxY) {
            throw new IllegalArgumentException("影像范围不在 Web Mercator 有效纬度范围内");
        }
        return new ReferencedEnvelope(bounds.getMinX(), bounds.getMaxX(), minY, maxY, WGS84);
    }

    private ReferencedEnvelope clampTarget(ReferencedEnvelope bounds,
            ImageryTileOptions.TargetCrs targetCrs) {
        double minX = Math.max(worldMinX(targetCrs), bounds.getMinX());
        double maxX = Math.min(worldMaxX(targetCrs), bounds.getMaxX());
        double minY = Math.max(worldMinY(targetCrs), bounds.getMinY());
        double maxY = Math.min(worldMaxY(targetCrs), bounds.getMaxY());
        if (minX >= maxX || minY >= maxY) {
            throw new IllegalArgumentException("GeoTIFF 范围不在目标坐标系可处理区域内");
        }
        return new ReferencedEnvelope(minX, maxX, minY, maxY, targetCrs(targetCrs));
    }

    private ReferencedEnvelope alignToPixelGrid(ReferencedEnvelope bounds,
            ImageryTileOptions.TargetCrs targetCrs, double resolution) {
        double worldMinX = worldMinX(targetCrs);
        double worldMaxY = worldMaxY(targetCrs);
        double minX = worldMinX + Math.floor((bounds.getMinX() - worldMinX) / resolution) * resolution;
        double maxX = worldMinX + Math.ceil((bounds.getMaxX() - worldMinX) / resolution) * resolution;
        double maxY = worldMaxY - Math.floor((worldMaxY - bounds.getMaxY()) / resolution) * resolution;
        double minY = worldMaxY - Math.ceil((worldMaxY - bounds.getMinY()) / resolution) * resolution;
        return clampTarget(new ReferencedEnvelope(minX, maxX, minY, maxY, targetCrs(targetCrs)), targetCrs);
    }

    private GeneralParameterValue[] baseReadParameters() {
        ParameterValue<OverviewPolicy> overview = AbstractGridFormat.OVERVIEW_POLICY.createValue();
        overview.setValue(OverviewPolicy.IGNORE);
        ParameterValue<Boolean> useImageN = AbstractGridFormat.USE_IMAGEN_IMAGEREAD.createValue();
        useImageN.setValue(Boolean.TRUE);
        return new GeneralParameterValue[]{overview, useImageN};
    }

    private boolean isValidCache(Path optimized) {
        if (!Files.isRegularFile(optimized) || !Files.isReadable(optimized)) {
            return false;
        }
        try {
            overviewBuilder.validate(optimized, overviewMinimumSize);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String cacheKey(Path source, RasterInspection inspection,
            ImageryTileOptions options) throws IOException {
        String value = CACHE_VERSION + '|' + source + '|' + inspection.fileSize() + '|'
                + Files.getLastModifiedTime(source).toMillis() + '|' + options.targetCrs().code()
                + '|' + options.resampling() + '|' + options.maxZoom();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    private long estimatedUncompressedSize(GridCoverage2D coverage) {
        SampleModel sampleModel = coverage.getRenderedImage().getSampleModel();
        int bits = DataBuffer.getDataTypeSize(sampleModel.getDataType());
        long bytesPerPixel = Math.max(1L,
                ((long) sampleModel.getNumBands() * bits + 7L) / 8L);
        try {
            long pixels = Math.multiplyExact((long) coverage.getRenderedImage().getWidth(),
                    coverage.getRenderedImage().getHeight());
            return Math.multiplyExact(pixels, bytesPerPixel);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private double[] backgroundValues(GridCoverage2D coverage, int bands) {
        double[] sourceValues = CoverageUtilities.getBackgroundValues(coverage);
        double[] values = new double[Math.max(1, bands)];
        if (sourceValues == null || sourceValues.length == 0) {
            return values;
        }
        for (int index = 0; index < values.length; index++) {
            values[index] = sourceValues[Math.min(index, sourceValues.length - 1)];
        }
        return values;
    }

    private Interpolation interpolation(ImageryTileOptions.Resampling resampling) {
        return Interpolation.getInstance(resampling == ImageryTileOptions.Resampling.NEAREST
                ? Interpolation.INTERP_NEAREST : Interpolation.INTERP_BILINEAR);
    }

    private CoordinateReferenceSystem canonicalCrs(CoordinateReferenceSystem crs) {
        if (crs == null) {
            throw new IllegalArgumentException("GeoTIFF 缺少坐标参考系");
        }
        if (CRS.equalsIgnoreMetadata(crs, WGS84)) {
            return WGS84;
        }
        if (CRS.equalsIgnoreMetadata(crs, WEB_MERCATOR)) {
            return WEB_MERCATOR;
        }
        try {
            Integer code = CRS.lookupEpsgCode(crs, true);
            if (Integer.valueOf(4326).equals(code)) {
                return WGS84;
            }
            if (Integer.valueOf(3857).equals(code)) {
                return WEB_MERCATOR;
            }
        } catch (Exception ignored) {
            // 由后续统一错误说明当前不支持的坐标系。
        }
        throw new IllegalArgumentException("首期影像切片仅支持 EPSG:4326 或 EPSG:3857 源坐标系");
    }

    private CoordinateReferenceSystem targetCrs(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326 ? WGS84 : WEB_MERCATOR;
    }

    private double worldMinX(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326 ? -180d : -WEB_MERCATOR_LIMIT;
    }

    private double worldMaxX(ImageryTileOptions.TargetCrs targetCrs) {
        return -worldMinX(targetCrs);
    }

    private double worldMinY(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326 ? -90d : -WEB_MERCATOR_LIMIT;
    }

    private double worldMaxY(ImageryTileOptions.TargetCrs targetCrs) {
        return -worldMinY(targetCrs);
    }

    private double worldHeight(ImageryTileOptions.TargetCrs targetCrs) {
        return worldMaxY(targetCrs) - worldMinY(targetCrs);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static CoordinateReferenceSystem decode(String code) {
        try {
            return CRS.decode(code, true);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public record PreparedRaster(Path path, boolean optimized, boolean cacheHit) { }

    private record RasterInspection(
            long fileSize,
            long pixelCount,
            int width,
            int height,
            int bands,
            CoordinateReferenceSystem sourceCrs,
            CoordinateReferenceSystem targetCrs,
            ReferencedEnvelope sourceBounds,
            boolean efficientTiles,
            boolean hasOverviews,
            boolean reprojectionRequired) { }
}
