package org.ocean.admin.gis.imagery;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.imagen.ImageLayout;
import org.eclipse.imagen.Interpolation;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.processing.Operations;
import org.geotools.coverage.util.CoverageUtilities;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.ocean.admin.gis.progress.TaskProgressModel;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** 将任意物理布局的 GeoTIFF 确定性地标准化为有界、目标网格对齐的分块影像。 */
@Slf4j
public class GeoTiffPreprocessor {

    private static final String CACHE_VERSION = "v8-target-derived-read-window";
    private static final String COMPLETE_MARKER = "complete.marker";
    private static final int TIFF_TILE_SIZE = 256;
    private static final int MAX_ZOOM = ImageryTileOptions.MAX_ZOOM;
    private static final double WEB_MERCATOR_LIMIT = 20_037_508.342789244;
    private static final double WEB_MERCATOR_MAX_LATITUDE = 85.0511287798066;
    private static final CoordinateReferenceSystem WGS84 = decode("EPSG:4326");
    private static final CoordinateReferenceSystem WEB_MERCATOR = decode("EPSG:3857");
    private static final ConcurrentHashMap<String, ReentrantLock> JVM_CACHE_LOCKS =
            new ConcurrentHashMap<>();

    private final Path cacheRoot;
    private final boolean enabled;
    private final long maxWindowPixels;
    private final int overviewMinimumSize;
    private final GeoTiffOverviewBuilder overviewBuilder;

    public GeoTiffPreprocessor(GisProcessingProperties properties,
            GeoTiffOverviewBuilder overviewBuilder) {
        Path processingRoot = Path.of(properties.getBasePath()).toAbsolutePath().normalize();
        Path configuredCache = Path.of(properties.getImageryCachePath());
        this.cacheRoot = (configuredCache.isAbsolute()
                ? configuredCache : processingRoot.resolve(configuredCache)).normalize();
        this.enabled = properties.isImageryOptimizationEnabled();
        this.maxWindowPixels = properties.getImageryPreprocessingMaxWindowPixels();
        this.overviewMinimumSize = properties.getImageryOverviewMinSize();
        this.overviewBuilder = overviewBuilder;
    }

    public PreparedRaster prepare(Path input, ImageryTileOptions options,
            Consumer<TaskProgressModel> progressListener) {
        long startedAt = System.nanoTime();
        Path source = input.toAbsolutePath().normalize();
        GeoTiffReader reader = null;
        try {
            reader = new GeoTiffReader(source.toFile());
            // 检查源栅格信息
            RasterInspection inspection = inspect(source, reader, options);
            log.info("[Imagery][Preprocess] 影像检查完成，尺寸: {}x{}，波段: {}，内部块: {}x{}，"
                            + "已有概览: {}，需要重投影: {}",
                    inspection.width(), inspection.height(), inspection.bands(),
                    inspection.blockWidth(), inspection.blockHeight(),
                    inspection.hasOverviews(), inspection.reprojectionRequired());
            if (!enabled) {
                // 如果未启用，则直接返回源栅格信息
                return PreparedRaster.direct(source, inspection.sourceBounds(),
                        inspection.width(), inspection.height());
            }

            String key = cacheKey(source, inspection, options);
            ReentrantLock jvmLock = JVM_CACHE_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
            jvmLock.lock();
            try {
                return prepareLocked(source, reader, inspection, options,
                        progressListener, key, startedAt);
            } finally {
                jvmLock.unlock();
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

//    核心方法：持有文件锁的情况下，安全地生成或复用影像分块优化缓存，并提供了进度反馈和异常清理
    private PreparedRaster prepareLocked(Path source, GeoTiffReader reader,
            RasterInspection inspection, ImageryTileOptions options,
            Consumer<TaskProgressModel> progressListener, String key,
            long startedAt) throws Exception {
        Path cacheDirectory = cacheRoot.resolve(key);
        Files.createDirectories(cacheDirectory);
        Path lockPath = cacheDirectory.resolve("preparation.lock");
        Path generationDirectory = null;
        try (FileChannel lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = lockChannel.lock()) {
            PreparedRaster cached = loadValidCache(cacheDirectory, inspection);
            if (cached != null) {
                progressListener.accept(TaskProgressModel.indeterminate(
                        "preparing", "已复用影像分块优化缓存"));
                log.info("[Imagery][Preprocess] 命中分块缓存，分块数: {}，耗时: {} ms",
                        cached.parts().size(), elapsedMillis(startedAt));
                return cached;
            }

            Files.deleteIfExists(cacheDirectory.resolve(COMPLETE_MARKER));
            String generationName = "parts-" + UUID.randomUUID();
            generationDirectory = cacheDirectory.resolve(generationName);
            Files.createDirectories(generationDirectory);

            ReadPlan plan = createReadPlan(inspection, options);
            progressListener.accept(TaskProgressModel.workload(
                    "preparing", 0, plan.windows().size(),
                    "影像预处理工作量已确定，共" + plan.windows().size() + "个分块"));
            log.info("[Imagery][Preprocess] 开始有界分块处理，分块数: {}，最大窗口像素: {}，"
                            + "全局目标尺寸: {}x{}",
                    plan.windows().size(), maxWindowPixels,
                    plan.globalTargetGrid().getGridRange2D().width,
                    plan.globalTargetGrid().getGridRange2D().height);

            List<PreparedPart> parts = new ArrayList<>(plan.windows().size());
            try (DirectWindowReader windowReader = new DirectWindowReader(
                    source, inspection.width(), inspection.height())) {
                for (ReadWindow window : plan.windows()) {
                    PreparedPart part = preparePart(windowReader, inspection, options,
                            generationDirectory, window);
                    parts.add(part);
                    progressListener.accept(TaskProgressModel.workload(
                            "preparing", window.sequence() + 1L, plan.windows().size(),
                            "正在标准化影像分块：" + (window.sequence() + 1)
                                    + "/" + plan.windows().size()));
                }
            }

            Files.writeString(cacheDirectory.resolve(COMPLETE_MARKER), generationName,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            cleanupStaleGenerations(cacheDirectory, generationName);
            log.info("[Imagery][Preprocess] 有界分块处理完成，分块数: {}，总耗时: {} ms",
                    parts.size(), elapsedMillis(startedAt));
            return new PreparedRaster(List.copyOf(parts), inspection.targetBounds(),
                    inspection.width(), inspection.height(), true, false, false);
        } catch (Exception ex) {
            Files.deleteIfExists(cacheDirectory.resolve(COMPLETE_MARKER));
            try {
                if (generationDirectory != null) {
                    deleteRecursively(generationDirectory);
                }
            } catch (IOException cleanupException) {
                ex.addSuppressed(cleanupException);
                log.warn("[Imagery][Preprocess] 未完成分块暂时无法清理: {}",
                        generationDirectory, cleanupException);
            }
            throw ex;
        }
    }

    private PreparedPart preparePart(DirectWindowReader windowReader,
            RasterInspection inspection, ImageryTileOptions options,
            Path partsDirectory, ReadWindow window) throws Exception {
        long startedAt = System.nanoTime();
        GridCoverage2D boundedSource = null;
        GridCoverage2D projected = null;
        Path partPath = partsDirectory.resolve("part-%06d.tif".formatted(window.sequence()));
        try {
            boundedSource = readWindow(windowReader, inspection, window.sourceRead());
            projected = (GridCoverage2D) Operations.DEFAULT.resample(
                    boundedSource, inspection.targetCrs(), window.targetGrid(),
                    interpolation(options.resampling()),
                    backgroundValues(boundedSource, inspection.bands()));
            writeCoverage(projected, partPath);
            int overviews = overviewBuilder.build(partPath, overviewMinimumSize, ignored -> { });
            overviewBuilder.validate(partPath, overviewMinimumSize);
            log.info("[Imagery][Preprocess][{}/{}] 分块完成，源核心: {}，源读取: {}，"
                            + "目标尺寸: {}x{}，概览层数: {}，耗时: {} ms",
                    window.sequence() + 1, window.total(), window.sourceCore(), window.sourceRead(),
                    window.targetGrid().getGridRange2D().width,
                    window.targetGrid().getGridRange2D().height,
                    overviews, elapsedMillis(startedAt));
            return new PreparedPart(partPath, window.targetBounds(), window.sequence(),
                    window.targetGrid().getGridRange2D().width,
                    window.targetGrid().getGridRange2D().height, overviews);
        } finally {
            if (projected != null) {
                projected.dispose(true);
            }
            if (boundedSource != null) {
                boundedSource.dispose(true);
            }
        }
    }

    private GridCoverage2D readWindow(DirectWindowReader windowReader,
            RasterInspection inspection, GridEnvelope2D region) throws Exception {
        ReferencedEnvelope envelope = gridEnvelope(inspection.sourceGrid(), region,
                inspection.sourceCrs());
        BufferedImage image = windowReader.read(region);
        int actualWidth = image.getWidth();
        int actualHeight = image.getHeight();
        int actualBands = image.getSampleModel().getNumBands();
        if (actualWidth != region.width || actualHeight != region.height
                || actualBands != inspection.bands()) {
            image.flush();
            throw new IOException("GeoTIFF 窗口读取结果与请求不一致，期望 "
                    + region.width + "x" + region.height + "x" + inspection.bands()
                    + "，实际 " + actualWidth + "x" + actualHeight + "x" + actualBands);
        }
        return new GridCoverageFactory().create("source-window", image, envelope);
    }

    private void writeCoverage(GridCoverage2D coverage, Path output) throws IOException {
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        GridCoverage2D materialized = materializeCoverage(coverage);
        GeoTiffWriter writer = null;
        try {
            GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
            writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParams.setCompressionType("Deflate");
            writeParams.setCompressionQuality(0.6f);
            writeParams.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
            int tileWidth = Math.min(TIFF_TILE_SIZE, materialized.getRenderedImage().getWidth());
            int tileHeight = Math.min(TIFF_TILE_SIZE, materialized.getRenderedImage().getHeight());
            writeParams.setTiling(Math.max(1, tileWidth), Math.max(1, tileHeight));
            writeParams.setForceToBigTIFF(false);

            GeoTiffFormat format = new GeoTiffFormat();
            var parameters = format.getWriteParameters();
            parameters.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString())
                    .setValue(writeParams);
            writer = new GeoTiffWriter(temporary.toFile());
            writer.write(materialized,
                    parameters.values().toArray(new GeneralParameterValue[0]));
        } finally {
            if (writer != null) {
                writer.dispose();
            }
            materialized.dispose(true);
        }
        moveAtomically(temporary, output);
    }

    private GridCoverage2D materializeCoverage(GridCoverage2D coverage) throws IOException {
        RenderedImage source = coverage.getRenderedImage();
        ColorModel colorModel = source.getColorModel();
        if (colorModel == null) {
            throw new IOException("重投影影像缺少颜色模型");
        }
        WritableRaster canonicalRaster = colorModel.createCompatibleWritableRaster(
                source.getWidth(), source.getHeight());
        WritableRaster sourceAlignedRaster = canonicalRaster.createWritableTranslatedChild(
                source.getMinX(), source.getMinY());
        source.copyData(sourceAlignedRaster);
        BufferedImage image = new BufferedImage(colorModel, canonicalRaster,
                colorModel.isAlphaPremultiplied(), null);
        GridGeometry2D canonicalGeometry = coverage.getGridGeometry().toCanonical();
        validateCanonicalGrid(source, image, canonicalGeometry);
        return new GridCoverageFactory().create(coverage.getName(), image,
                canonicalGeometry, coverage.getSampleDimensions(), null, null);
    }

    private void validateCanonicalGrid(RenderedImage source, BufferedImage image,
            GridGeometry2D geometry) throws IOException {
        GridEnvelope2D range = geometry.getGridRange2D();
        if (image.getMinX() != range.x || image.getMinY() != range.y
                || image.getWidth() != range.width || image.getHeight() != range.height) {
            throw new IOException("重投影影像规范化后网格不一致，源图像范围: "
                    + imageRange(source) + "，规范化图像范围: " + imageRange(image)
                    + "，规范化网格范围: " + gridRange(range));
        }
    }

    private String imageRange(RenderedImage image) {
        return "[x=" + image.getMinX() + ".." + (image.getMinX() + image.getWidth())
                + ", y=" + image.getMinY() + ".." + (image.getMinY() + image.getHeight())
                + ")";
    }

    private String gridRange(GridEnvelope2D range) {
        return "[x=" + range.x + ".." + (range.x + range.width)
                + ", y=" + range.y + ".." + (range.y + range.height) + ")";
    }

    private ReadPlan createReadPlan(RasterInspection inspection,
            ImageryTileOptions options) throws Exception {
        GridGeometry2D globalTargetGrid = targetGrid(inspection, options);
        int width = inspection.width();
        int height = inspection.height();
        int blockWidth = inspection.blockWidth();
        int blockHeight = inspection.blockHeight();
        long blockPixels = Math.multiplyExact((long) blockWidth, blockHeight);
        long maxBlocks = Math.max(1L, maxWindowPixels / blockPixels);

        int blocksX = divideCeil(width, blockWidth);
        int blocksY = divideCeil(height, blockHeight);
        int windowBlocksX;
        int windowBlocksY;
        if (blockWidth >= width) {
            windowBlocksX = 1;
            windowBlocksY = (int) Math.min(blocksY, maxBlocks);
        } else {
            double squareBlockColumns = Math.sqrt(maxBlocks
                    * ((double) blockHeight / blockWidth));
            windowBlocksX = Math.max(1,
                    Math.min(blocksX, (int) Math.floor(squareBlockColumns)));
            windowBlocksY = Math.max(1, Math.min(blocksY,
                    (int) Math.max(1L, maxBlocks / windowBlocksX)));
        }
        int windowWidth = Math.multiplyExact(windowBlocksX, blockWidth);
        int windowHeight = Math.multiplyExact(windowBlocksY, blockHeight);
        int interpolationRadius = options.resampling() == ImageryTileOptions.Resampling.NEAREST
                ? 0 : 1;

        List<ReadWindowSeed> seeds = new ArrayList<>();
        for (int y = 0; y < height; y += windowHeight) {
            for (int x = 0; x < width; x += windowWidth) {
                GridEnvelope2D core = new GridEnvelope2D(x, y,
                        Math.min(windowWidth, width - x),
                        Math.min(windowHeight, height - y));
                ReferencedEnvelope coreEnvelope = gridEnvelope(
                        inspection.sourceGrid(), core, inspection.sourceCrs());
                ReferencedEnvelope transformed = clampTarget(
                        coreEnvelope.transform(inspection.targetCrs(), true),
                        options.targetCrs());
                GridGeometry2D partGrid = alignedPartGrid(globalTargetGrid, transformed,
                        inspection.targetCrs());
                GridEnvelope2D read = sourceReadWindow(inspection, core, partGrid,
                        interpolationRadius);
                seeds.add(new ReadWindowSeed(core, read, partGrid,
                        new ReferencedEnvelope(partGrid.getEnvelope2D())));
            }
        }
        List<ReadWindow> windows = new ArrayList<>(seeds.size());
        for (int index = 0; index < seeds.size(); index++) {
            ReadWindowSeed seed = seeds.get(index);
            windows.add(new ReadWindow(seed.sourceCore(), seed.sourceRead(),
                    seed.targetGrid(), seed.targetBounds(), index, seeds.size()));
        }
        return new ReadPlan(globalTargetGrid, List.copyOf(windows));
    }

    private GridEnvelope2D sourceReadWindow(RasterInspection inspection,
            GridEnvelope2D sourceCore, GridGeometry2D targetGrid,
            int interpolationRadius) throws Exception {
        ReferencedEnvelope requiredSourceBounds = new ReferencedEnvelope(
                targetGrid.getEnvelope2D()).transform(inspection.sourceCrs(), true);
        GridEnvelope2D requiredSourceGrid = inspection.sourceGrid()
                .worldToGrid(requiredSourceBounds);

        int minX = Math.min(sourceCore.x, requiredSourceGrid.x);
        int minY = Math.min(sourceCore.y, requiredSourceGrid.y);
        int maxX = Math.max(sourceCore.x + sourceCore.width,
                requiredSourceGrid.x + requiredSourceGrid.width);
        int maxY = Math.max(sourceCore.y + sourceCore.height,
                requiredSourceGrid.y + requiredSourceGrid.height);
        GridEnvelope2D required = new GridEnvelope2D(
                minX, minY, maxX - minX, maxY - minY);
        return expand(required, interpolationRadius,
                inspection.width(), inspection.height());
    }

    private GridGeometry2D alignedPartGrid(GridGeometry2D global,
            ReferencedEnvelope bounds, CoordinateReferenceSystem targetCrs) {
        GridEnvelope2D grid = global.getGridRange2D();
        ReferencedEnvelope globalBounds = new ReferencedEnvelope(global.getEnvelope2D());
        double resolutionX = globalBounds.getWidth() / grid.width;
        double resolutionY = globalBounds.getHeight() / grid.height;
        int minX = clamp((int) Math.floor((bounds.getMinX() - globalBounds.getMinX())
                / resolutionX), 0, grid.width - 1);
        int maxX = clamp((int) Math.ceil((bounds.getMaxX() - globalBounds.getMinX())
                / resolutionX), minX + 1, grid.width);
        int minY = clamp((int) Math.floor((globalBounds.getMaxY() - bounds.getMaxY())
                / resolutionY), 0, grid.height - 1);
        int maxY = clamp((int) Math.ceil((globalBounds.getMaxY() - bounds.getMinY())
                / resolutionY), minY + 1, grid.height);
        ReferencedEnvelope aligned = new ReferencedEnvelope(
                globalBounds.getMinX() + minX * resolutionX,
                globalBounds.getMinX() + maxX * resolutionX,
                globalBounds.getMaxY() - maxY * resolutionY,
                globalBounds.getMaxY() - minY * resolutionY,
                targetCrs);
        return new GridGeometry2D(new GridEnvelope2D(0, 0,
                maxX - minX, maxY - minY), aligned);
    }

    private PreparedRaster loadValidCache(Path cacheDirectory,
            RasterInspection inspection) {
        if (!Files.isRegularFile(cacheDirectory.resolve(COMPLETE_MARKER))) {
            return null;
        }
        String generationName;
        try {
            generationName = Files.readString(cacheDirectory.resolve(COMPLETE_MARKER),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return null;
        }
        if (!generationName.startsWith("parts-") || generationName.contains("..")
                || generationName.contains("/") || generationName.contains("\\")) {
            return null;
        }
        Path partsDirectory = cacheDirectory.resolve(generationName);
        try (Stream<Path> paths = Files.list(partsDirectory)) {
            List<Path> partPaths = paths
                    .filter(path -> path.getFileName().toString().endsWith(".tif"))
                    .sorted().toList();
            if (partPaths.isEmpty()) {
                return null;
            }
            List<PreparedPart> parts = new ArrayList<>(partPaths.size());
            for (int index = 0; index < partPaths.size(); index++) {
                Path path = partPaths.get(index);
                int overviews = overviewBuilder.validate(path, overviewMinimumSize);
                GeoTiffReader partReader = new GeoTiffReader(path.toFile());
                try {
                    parts.add(new PreparedPart(path,
                            new ReferencedEnvelope(partReader.getOriginalEnvelope()), index,
                            partReader.getOriginalGridRange().getSpan(0),
                            partReader.getOriginalGridRange().getSpan(1), overviews));
                } finally {
                    partReader.dispose();
                }
            }
            return new PreparedRaster(List.copyOf(parts), inspection.targetBounds(),
                    inspection.width(), inspection.height(), true, true, false);
        } catch (Exception ex) {
            log.warn("[Imagery][Preprocess] 分块缓存无效，将重新生成: {}", cacheDirectory, ex);
            return null;
        }
    }

    private RasterInspection inspect(Path source, GeoTiffReader reader,
            ImageryTileOptions options) throws Exception {
        GridEnvelope range = reader.getOriginalGridRange();
        int width = range.getSpan(0);
        int height = range.getSpan(1);
        CoordinateReferenceSystem sourceCrs = canonicalCrs(reader.getCoordinateReferenceSystem());
        CoordinateReferenceSystem targetCrs = targetCrs(options.targetCrs());
        ImageLayout layout = reader.getImageLayout();
        int blockWidth = layout.isValid(ImageLayout.TILE_WIDTH_MASK)
                ? layout.getTileWidth(null) : width;
        int blockHeight = layout.isValid(ImageLayout.TILE_HEIGHT_MASK)
                ? layout.getTileHeight(null) : height;
        blockWidth = clamp(blockWidth, 1, width);
        blockHeight = clamp(blockHeight, 1, height);
        double[][] resolutions = reader.getResolutionLevels();
        boolean hasOverviews = resolutions != null && resolutions.length > 1;
        int bands = layout.getSampleModel(null) == null
                ? 4 : layout.getSampleModel(null).getNumBands();
        ReferencedEnvelope sourceBounds = new ReferencedEnvelope(reader.getOriginalEnvelope())
                .transform(sourceCrs, true);
        ReferencedEnvelope targetBounds = clampTarget(
                validSourceBounds(sourceBounds, sourceCrs, targetCrs)
                        .transform(targetCrs, true), options.targetCrs());
        return new RasterInspection(Files.size(source), width, height, bands,
                sourceCrs, targetCrs, sourceBounds, targetBounds,
                new GridGeometry2D(reader.getOriginalGridRange(), reader.getOriginalEnvelope()),
                hasOverviews, !CRS.equalsIgnoreMetadata(sourceCrs, targetCrs),
                blockWidth, blockHeight);
    }

    private GridGeometry2D targetGrid(RasterInspection inspection,
            ImageryTileOptions options) throws Exception {
        ReferencedEnvelope bounded = inspection.targetBounds();
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
        double minX = worldMinX(targetCrs)
                + Math.floor((bounds.getMinX() - worldMinX(targetCrs)) / resolution) * resolution;
        double maxX = worldMinX(targetCrs)
                + Math.ceil((bounds.getMaxX() - worldMinX(targetCrs)) / resolution) * resolution;
        double maxY = worldMaxY(targetCrs)
                - Math.floor((worldMaxY(targetCrs) - bounds.getMaxY()) / resolution) * resolution;
        double minY = worldMaxY(targetCrs)
                - Math.ceil((worldMaxY(targetCrs) - bounds.getMinY()) / resolution) * resolution;
        return clampTarget(new ReferencedEnvelope(minX, maxX, minY, maxY,
                targetCrs(targetCrs)), targetCrs);
    }

    private ReferencedEnvelope gridEnvelope(GridGeometry2D grid,
            GridEnvelope2D region, CoordinateReferenceSystem crs) throws Exception {
        return new ReferencedEnvelope(grid.gridToWorld(region), crs);
    }

    private GridEnvelope2D expand(GridEnvelope2D core, int radius,
            int width, int height) {
        int minX = Math.max(0, core.x - radius);
        int minY = Math.max(0, core.y - radius);
        int maxX = Math.min(width, core.x + core.width + radius);
        int maxY = Math.min(height, core.y + core.height + radius);
        return new GridEnvelope2D(minX, minY, maxX - minX, maxY - minY);
    }

    private double[] backgroundValues(GridCoverage2D coverage, int bands) {
        double[] sourceValues = CoverageUtilities.getBackgroundValues(coverage);
        double[] values = new double[Math.max(1, bands)];
        if (sourceValues != null && sourceValues.length > 0) {
            for (int index = 0; index < values.length; index++) {
                values[index] = sourceValues[Math.min(index, sourceValues.length - 1)];
            }
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
        return crs;
    }

    private CoordinateReferenceSystem targetCrs(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326
                ? WGS84 : WEB_MERCATOR;
    }

    /**
     * 为栅格瓦片缓存生成一个唯一且稳定的缓存键
     * @param source 源栅格文件路径
     * @param inspection 对源文件进行检查后得到的信息
     * @param options 瓦片生成选项
     * @return 缓存键
     * @throws IOException io异常
     */
    private String cacheKey(Path source, RasterInspection inspection,
            ImageryTileOptions options) throws IOException {
        String value = CACHE_VERSION + '|' + source + '|' + inspection.fileSize() + '|'
                + Files.getLastModifiedTime(source).toMillis() + '|' + options.targetCrs().code()
                + '|' + options.resampling() + '|' + options.maxZoom() + '|' + maxWindowPixels;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    private void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void cleanupStaleGenerations(Path cacheDirectory, String activeGeneration) {
        try (Stream<Path> paths = Files.list(cacheDirectory)) {
            for (Path path : paths.filter(Files::isDirectory)
                    .filter(candidate -> candidate.getFileName().toString().startsWith("parts-"))
                    .filter(candidate -> !candidate.getFileName().toString()
                            .equals(activeGeneration)).toList()) {
                try {
                    deleteRecursively(path);
                } catch (IOException ex) {
                    log.debug("[Imagery][Preprocess] 旧缓存代次暂时无法清理: {}", path, ex);
                }
            }
        } catch (IOException ex) {
            log.debug("[Imagery][Preprocess] 无法枚举旧缓存代次: {}", cacheDirectory, ex);
        }
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

    private int divideCeil(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private double worldMinX(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326
                ? -180.0 : -WEB_MERCATOR_LIMIT;
    }

    private double worldMaxX(ImageryTileOptions.TargetCrs targetCrs) {
        return -worldMinX(targetCrs);
    }

    private double worldMinY(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326
                ? -90.0 : -WEB_MERCATOR_LIMIT;
    }

    private double worldMaxY(ImageryTileOptions.TargetCrs targetCrs) {
        return -worldMinY(targetCrs);
    }

    private double worldHeight(ImageryTileOptions.TargetCrs targetCrs) {
        return worldMaxY(targetCrs) - worldMinY(targetCrs);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** 使用已注册的 TIFF ImageReader 对原始影像执行真正的源区域读取。 */
    private static final class DirectWindowReader implements AutoCloseable {
        private final ImageInputStream input;
        private final ImageReader reader;

        private DirectWindowReader(Path source, int expectedWidth, int expectedHeight)
                throws IOException {
            ImageInputStream createdInput = ImageIO.createImageInputStream(source.toFile());
            if (createdInput == null) {
                throw new IOException("无法创建 GeoTIFF 输入流: " + source);
            }
            ImageReader createdReader = null;
            try {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(createdInput);
                if (!readers.hasNext()) {
                    throw new IOException("当前 JVM 没有可用的 TIFF ImageReader: " + source);
                }
                createdReader = readers.next();
                createdReader.setInput(createdInput, false, true);
                int actualWidth = createdReader.getWidth(0);
                int actualHeight = createdReader.getHeight(0);
                if (actualWidth != expectedWidth || actualHeight != expectedHeight) {
                    throw new IOException("GeoTIFF ImageReader 尺寸与元数据不一致，期望 "
                            + expectedWidth + "x" + expectedHeight + "，实际 "
                            + actualWidth + "x" + actualHeight);
                }
                this.input = createdInput;
                this.reader = createdReader;
            } catch (Exception ex) {
                if (createdReader != null) {
                    createdReader.dispose();
                }
                createdInput.close();
                if (ex instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("初始化 GeoTIFF 窗口读取器失败: " + source, ex);
            }
        }

        private BufferedImage read(GridEnvelope2D region) throws IOException {
            ImageReadParam parameters = reader.getDefaultReadParam();
            parameters.setSourceRegion(new Rectangle(
                    region.x, region.y, region.width, region.height));
            BufferedImage image = reader.read(0, parameters);
            if (image == null) {
                throw new IOException("GeoTIFF ImageReader 返回空窗口: " + region);
            }
            return image;
        }

        @Override
        public void close() throws IOException {
            reader.dispose();
            input.close();
        }
    }

    public record PreparedRaster(
            List<PreparedPart> parts,
            ReferencedEnvelope bounds,
            int sourceWidth,
            int sourceHeight,
            boolean optimized,
            boolean cacheHit,
            boolean singleSource) {

        public PreparedRaster {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("预处理影像分块不能为空");
            }
        }

        /**
         * 将单个源栅格文件（Path source）直接映射到指定的地理范围（ReferencedEnvelope bounds）和像素尺寸（width, height）
         * @param source 源栅格文件
         * @param bounds 地理范围
         * @param width 像素尺寸宽度
         * @param height 像素尺寸高度
         * @return 构造好的栅格信息
         */
        public static PreparedRaster direct(Path source, ReferencedEnvelope bounds,
                int width, int height) {
            return new PreparedRaster(List.of(new PreparedPart(
                    source, bounds, 0, width, height, 0)),
                    bounds, width, height, false, false, true);
        }
    }

    public record PreparedPart(
            Path path,
            ReferencedEnvelope bounds,
            int order,
            int width,
            int height,
            int overviewCount) { }

    private record RasterInspection(
            long fileSize,
            int width,
            int height,
            int bands,
            CoordinateReferenceSystem sourceCrs,
            CoordinateReferenceSystem targetCrs,
            ReferencedEnvelope sourceBounds,
            ReferencedEnvelope targetBounds,
            GridGeometry2D sourceGrid,
            boolean hasOverviews,
            boolean reprojectionRequired,
            int blockWidth,
            int blockHeight) { }

    private record ReadPlan(GridGeometry2D globalTargetGrid, List<ReadWindow> windows) { }

    private record ReadWindow(
            GridEnvelope2D sourceCore,
            GridEnvelope2D sourceRead,
            GridGeometry2D targetGrid,
            ReferencedEnvelope targetBounds,
            int sequence,
            int total) { }

    private record ReadWindowSeed(
            GridEnvelope2D sourceCore,
            GridEnvelope2D sourceRead,
            GridGeometry2D targetGrid,
            ReferencedEnvelope targetBounds) { }
}
