package org.ocean.admin.gis.imagery;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.imagen.Interpolation;
import org.eclipse.imagen.ImageN;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.style.RasterSymbolizer;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.OverviewPolicy;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.renderer.lite.RendererUtilities;
import org.geotools.renderer.lite.gridcoverage2d.GridCoverageRenderer;
import org.geotools.styling.StyleBuilder;
import org.locationtech.jts.index.strtree.STRtree;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.admin.gis.progress.ProgressStage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** 使用 GeoTools、Eclipse ImageN 与 ImageIO 将普通 GeoTIFF 生成为静态影像瓦片集。 */
@Slf4j
public class GeoTiffTileGenerator {

    private static final double WEB_MERCATOR_LIMIT = 20_037_508.342789244;
    private static final double GEOGRAPHIC_BOUNDARY_TOLERANCE = 1e-6;
    private static final int META_TILE_SIZE = 4;
    private static final int MIN_VISIBLE_PIXELS = 1;
    private static final long PROGRESS_REPORT_INTERVAL_NANOS = 2_000_000_000L;
    private static final CoordinateReferenceSystem WEB_MERCATOR = decode("EPSG:3857");
    private static final CoordinateReferenceSystem WGS84 = decode("EPSG:4326");

    private final ObjectMapper objectMapper;
    private final long maxTileCount;

    public GeoTiffTileGenerator(ObjectMapper objectMapper, long maxTileCount) {
        this.objectMapper = objectMapper;
        if (maxTileCount <= 0) {
            throw new IllegalArgumentException("影像单任务瓦片上限必须大于0");
        }
        this.maxTileCount = maxTileCount;
    }

    /** 在读取大量像素前，按源分辨率规划各层覆盖范围并去重。 */
    public MosaicPlan plan(List<Path> inputs, ImageryTileOptions options) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("影像切片至少需要一个 GeoTIFF");
        }
        List<SourceRaster> sources = new ArrayList<>();
        ReferencedEnvelope bounds = new ReferencedEnvelope(coordinateReferenceSystem(options.targetCrs()));
        ReferencedEnvelope geographicBounds = new ReferencedEnvelope(WGS84);
        try {
            for (Path input : inputs) {
                Path path = input.toAbsolutePath().normalize();
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                    throw new IllegalArgumentException("GeoTIFF 不存在或不可读: " + path);
                }
                GeoTiffReader reader = new GeoTiffReader(path.toFile());
                try {
                    RasterSource raster = inspect(reader);
                    ReferencedEnvelope sourceBounds = raster.bounds();
                    if (options.targetCrs() == ImageryTileOptions.TargetCrs.EPSG_3857
                            && CRS.equalsIgnoreMetadata(sourceBounds.getCoordinateReferenceSystem(), WGS84)) {
                        sourceBounds = new ReferencedEnvelope(sourceBounds.getMinX(), sourceBounds.getMaxX(),
                                Math.max(-85.0511287798066, sourceBounds.getMinY()),
                                Math.min(85.0511287798066, sourceBounds.getMaxY()), WGS84);
                    }
                    ReferencedEnvelope target = clampTarget(sourceBounds.transform(
                            coordinateReferenceSystem(options.targetCrs()), true), options.targetCrs());
                    ReferencedEnvelope geographic = target.transform(WGS84, true);
                    double resolutionX = targetResolution(raster.resolutionX(),
                            sourceBounds.getCoordinateReferenceSystem(), options.targetCrs());
                    double resolutionY = targetResolution(raster.resolutionY(),
                            sourceBounds.getCoordinateReferenceSystem(), options.targetCrs());
                    double nominalResolution = Math.max(resolutionX, resolutionY);
                    int nativeZoom = Math.max(0, Math.min(ImageryTileOptions.MAX_ZOOM,
                            ImageryTileOptions.calculateNativeMaxZoom(nominalResolution,
                                    worldHeight(options.targetCrs()), ImageryTileOptions.TILE_SIZE)));
                    sources.add(new SourceRaster(path, target, nativeZoom,
                            nominalResolution, resolutionX * resolutionY, sources.size(),
                            Files.size(path), Files.getLastModifiedTime(path)));
                    bounds.expandToInclude(target);
                    geographicBounds.expandToInclude(geographic);
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法分析影像集合: " + ex.getMessage(), ex);
        }
        int max = options.maxZoom() == null
                ? sources.stream().mapToInt(SourceRaster::nativeZoom).max().orElseThrow()
                : options.maxZoom();
        int min = options.minZoom() == null
                ? Math.min(max, calculateMinimumZoom(bounds, options.targetCrs())) : options.minZoom();
        if (min > max) {
            throw new IllegalArgumentException("影像最小层级不能大于集合最大层级");
        }

        List<PlannedTile> tiles = new ArrayList<>();
        long count = 0;
        for (int zoom = min; zoom <= max; zoom++) {
            Map<Long, Integer> masks = new TreeMap<>();
            for (SourceRaster source : sources) {
                if (options.maxZoom() == null && zoom > source.nativeZoom()) {
                    continue;
                }
                TileRange range = tileRange(source.bounds(), zoom, options.targetCrs());
                for (int x = range.minX(); x <= range.maxX(); x++) {
                    for (int y = range.minY(); y <= range.maxY(); y++) {
                        long key = ((long) (x / META_TILE_SIZE) << 32) | (y / META_TILE_SIZE & 0xffffffffL);
                        int bit = 1 << ((y % META_TILE_SIZE) * META_TILE_SIZE + x % META_TILE_SIZE);
                        int previous = masks.getOrDefault(key, 0);
                        if ((previous & bit) == 0) {
                            masks.put(key, previous | bit);
                            if (++count > maxTileCount) {
                                throw new IllegalArgumentException("预计生成瓦片超过单任务上限" + maxTileCount
                                        + "张；可降低maxZoom或调整GIS_IMAGERY_MAX_TILE_COUNT");
                            }
                        }
                    }
                }
            }
            for (Map.Entry<Long, Integer> entry : masks.entrySet()) {
                int x = (int) (entry.getKey() >>> 32) * META_TILE_SIZE;
                int y = (int) (entry.getKey().longValue()) * META_TILE_SIZE;
                tiles.add(new PlannedTile(metaTileRange(zoom, x,
                        Math.min(x + META_TILE_SIZE - 1, options.targetCrs().matrixWidth(zoom) - 1),
                        y, Math.min(y + META_TILE_SIZE - 1, options.targetCrs().matrixHeight(zoom) - 1),
                        options.targetCrs()), entry.getValue()));
            }
        }
        log.info("[Imagery][Plan] 源文件: {}，层级: {}-{}，去重瓦片: {}，元瓦片: {}",
                sources.size(), min, max, count, tiles.size());
        return new MosaicPlan(List.copyOf(sources), geographicBounds,
                new ZoomRange(min, max), List.copyOf(tiles), count);
    }

    public void generate(MosaicPlan plan, List<GeoTiffPreprocessor.PreparedRaster> rasters,
            String name, Path output, ImageryTileOptions options, Consumer<ProgressStage> progressListener) {
        if (rasters.size() != plan.sources().size()) {
            throw new IllegalArgumentException("预处理结果与集合输入数量不一致");
        }
        Path target = output.toAbsolutePath().normalize();
        STRtree index = new STRtree();
        try {
            Files.createDirectories(target);
            for (int i = 0; i < rasters.size(); i++) {
                SourceRaster source = plan.sources().get(i);
                source.validateUnchanged();
                for (GeoTiffPreprocessor.PreparedPart part : rasters.get(i).parts()) {
                    if (!Files.isReadable(part.path())) {
                        throw new IllegalStateException("影像分块不可读: " + part.path());
                    }
                    ReferencedEnvelope bounds = part.bounds().transform(
                            coordinateReferenceSystem(options.targetCrs()), true);
                    index.insert(bounds, new IndexedPart(part, source, rasters.get(i).singleSource()));
                }
            }
            index.build();
            long completed = 0;
            long lastReport = 0;
            try (PartReaderCache readers = new PartReaderCache(8);
                    PartImageCache images = new PartImageCache(2)) {
                RasterSymbolizer symbolizer = new StyleBuilder().createRasterSymbolizer();
                Interpolation interpolation = interpolation(options.resampling());
                progressListener.accept(ProgressStage.determinate("generating", "开始生成集合影像瓦片",
                        0, plan.tileCount(), ProgressUnit.TILE));
                for (PlannedTile tile : plan.tiles()) {
                    BufferedImage rendered = renderMetaTile(index, readers, images,
                            symbolizer, interpolation, tile.range(), options);
                    try {
                        completed += splitAndWrite(rendered, tile, target, options);
                    } finally {
                        rendered.flush();
                    }
                    long now = System.nanoTime();
                    if (completed == plan.tileCount() || now - lastReport >= PROGRESS_REPORT_INTERVAL_NANOS) {
                        lastReport = now;
                        progressListener.accept(ProgressStage.determinate("generating",
                                "正在生成集合影像瓦片：" + completed + "/" + plan.tileCount(),
                                completed, plan.tileCount(), ProgressUnit.TILE));
                    }
                }
            }
            for (SourceRaster source : plan.sources()) {
                source.validateUnchanged();
            }
            if (completed != plan.tileCount()) {
                throw new IllegalStateException("实际瓦片数量与集合计划不一致");
            }
            progressListener.accept(ProgressStage.determinate("finalizing", "正在写入集合影像元数据",
                    completed, completed, ProgressUnit.TILE));
            writeMetadata(target, name, plan, options);
        } catch (Exception ex) {
            throw new IllegalStateException("影像集合切片失败: " + ex.getMessage(), ex);
        }
    }

    private RasterSource inspect(GeoTiffReader reader) throws Exception {
        CoordinateReferenceSystem sourceCrs = reader.getCoordinateReferenceSystem();
        CoordinateReferenceSystem canonicalSourceCrs = canonicalSourceCrs(sourceCrs);
        GridEnvelope gridRange = reader.getOriginalGridRange();
        if (gridRange == null || gridRange.getDimension() < 2
                || gridRange.getSpan(0) <= 0 || gridRange.getSpan(1) <= 0) {
            throw new IllegalArgumentException("GeoTIFF 栅格尺寸无效");
        }
        SampleModel sampleModel = reader.getImageLayout().getSampleModel(null);
        int bands = sampleModel == null ? 0 : sampleModel.getNumBands();
        if (bands < 1 || bands > 4) {
            throw new IllegalArgumentException("首期影像切片仅支持1到4个波段，当前: " + bands);
        }
        MathTransform gridToCrs = reader.getOriginalGridToWorld(PixelInCell.CELL_CENTER);
        if (gridToCrs instanceof AffineTransform affine
                && (Math.abs(affine.getShearX()) > 1e-10
                || Math.abs(affine.getShearY()) > 1e-10)) {
            throw new IllegalArgumentException("首期影像切片不支持旋转或错切的 GeoTIFF 仿射变换");
        }
        ReferencedEnvelope sourceBounds = normalizeSourceBounds(
                new ReferencedEnvelope(reader.getOriginalEnvelope())
                        .transform(canonicalSourceCrs, true),
                canonicalSourceCrs);
        double[] resolution = pixelResolution(gridToCrs, gridRange);
        return new RasterSource(sourceBounds, gridRange.getSpan(0), gridRange.getSpan(1),
                resolution[0], resolution[1]);
    }

    private double[] pixelResolution(MathTransform gridToCrs, GridEnvelope gridRange) throws Exception {
        double x = gridRange.getLow(0) + gridRange.getSpan(0) / 2d;
        double y = gridRange.getLow(1) + gridRange.getSpan(1) / 2d;
        double[] grid = {x, y, x + 1d, y, x, y + 1d};
        double[] world = new double[6];
        gridToCrs.transform(grid, 0, world, 0, 3);
        return new double[]{
                Math.hypot(world[2] - world[0], world[3] - world[1]),
                Math.hypot(world[4] - world[0], world[5] - world[1])};
    }

    private double targetResolution(double sourceResolution,
            CoordinateReferenceSystem sourceCrs, ImageryTileOptions.TargetCrs targetCrs) {
        boolean geographicSource = CRS.equalsIgnoreMetadata(sourceCrs, WGS84);
        if (geographicSource && targetCrs == ImageryTileOptions.TargetCrs.EPSG_3857) {
            return sourceResolution * ImageryTileOptions.WEB_MERCATOR_METERS_PER_DEGREE;
        }
        if (!geographicSource && targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326) {
            return sourceResolution / ImageryTileOptions.WEB_MERCATOR_METERS_PER_DEGREE;
        }
        return sourceResolution;
    }

    private BufferedImage renderMetaTile(STRtree index,
            PartReaderCache readerCache, PartImageCache imageCache,
            RasterSymbolizer symbolizer, Interpolation interpolation,
            MetaTileRange metaTile, ImageryTileOptions options) throws Exception {
        BufferedImage rendered = new BufferedImage(metaTile.pixelWidth(), metaTile.pixelHeight(),
                BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = rendered.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    options.resampling() == ImageryTileOptions.Resampling.NEAREST
                            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Rectangle rasterArea = new Rectangle(rendered.getWidth(), rendered.getHeight());
            AffineTransform worldToScreen = RendererUtilities.worldToScreenTransform(
                    metaTile.bounds(), rasterArea);
            List<IndexedPart> selected = new ArrayList<>();
            for (Object candidate : index.query(metaTile.bounds())) {
                selected.add((IndexedPart) candidate);
            }
            // 后绘制高分辨率源；同分辨率时按输入序号稳定覆盖，无效像素保留下层。
            selected.sort(Comparator.comparingDouble((IndexedPart part) -> part.source().pixelArea())
                    .reversed().thenComparingInt(part -> part.source().sequence())
                    .thenComparingInt(part -> part.part().order()));
            int next = 0;
            while (next < selected.size()) {
                int sequence = selected.get(next).source().sequence();
                BufferedImage layer = new BufferedImage(rendered.getWidth(), rendered.getHeight(),
                        BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D layerGraphics = layer.createGraphics();
                try {
                    layerGraphics.setRenderingHints(graphics.getRenderingHints());
                    // 同源分块可能因重投影采样边缘相交；透明像素不得擦除已绘制的有效像素。
                    while (next < selected.size() && selected.get(next).source().sequence() == sequence) {
                        IndexedPart part = selected.get(next++);
                        if (part.direct()) {
                            GridCoverageRenderer renderer = new GridCoverageRenderer(
                                    coordinateReferenceSystem(options.targetCrs()), metaTile.bounds(), rasterArea,
                                    worldToScreen, new RenderingHints(ImageN.KEY_INTERPOLATION, interpolation));
                            renderer.setAdvancedProjectionHandlingEnabled(false);
                            renderer.setWrapEnabled(false);
                            renderer.paint(layerGraphics, readerCache.reader(part.part().path()), readParameters(true),
                                    symbolizer, interpolation, null);
                        } else {
                            drawPreparedPart(layerGraphics, worldToScreen, imageCache, part.part(), metaTile);
                        }
                    }
                    graphics.drawImage(layer, 0, 0, null);
                } finally {
                    layerGraphics.dispose();
                    layer.flush();
                }
            }
        } finally {
            graphics.dispose();
        }
        return rendered;
    }

    private void drawPreparedPart(Graphics2D graphics, AffineTransform worldToScreen,
            PartImageCache imageCache, GeoTiffPreprocessor.PreparedPart part,
            MetaTileRange metaTile) throws IOException {
        int desiredWidth = Math.max(1, (int) Math.ceil(part.bounds().getWidth()
                * metaTile.pixelWidth() / metaTile.bounds().getWidth()));
        int desiredHeight = Math.max(1, (int) Math.ceil(part.bounds().getHeight()
                * metaTile.pixelHeight() / metaTile.bounds().getHeight()));
        BufferedImage image = imageCache.image(part, desiredWidth, desiredHeight);
        AffineTransform pixelToWorld = new AffineTransform(
                part.bounds().getWidth() / image.getWidth(), 0.0,
                0.0, -part.bounds().getHeight() / image.getHeight(),
                part.bounds().getMinX(), part.bounds().getMaxY());
        AffineTransform pixelToScreen = new AffineTransform(worldToScreen);
        pixelToScreen.concatenate(pixelToWorld);
        graphics.drawImage(image, pixelToScreen, null);
    }

    private int splitAndWrite(BufferedImage rendered, PlannedTile planned,
            Path output, ImageryTileOptions options) throws IOException {
        MetaTileRange metaTile = planned.range();
        int written = 0;
        for (int x = metaTile.minX(); x <= metaTile.maxX(); x++) {
            Path tileDirectory = output.resolve(Integer.toString(metaTile.zoom()))
                    .resolve(Integer.toString(x));
            Files.createDirectories(tileDirectory);
            for (int y = metaTile.minY(); y <= metaTile.maxY(); y++) {
                int bit = 1 << ((y - metaTile.minY()) * META_TILE_SIZE + x - metaTile.minX());
                if ((planned.mask() & bit) == 0) {
                    continue;
                }
                int sourceX = (x - metaTile.minX()) * ImageryTileOptions.TILE_SIZE;
                int sourceY = (y - metaTile.minY()) * ImageryTileOptions.TILE_SIZE;
                BufferedImage tile = rendered.getSubimage(sourceX, sourceY,
                        ImageryTileOptions.TILE_SIZE, ImageryTileOptions.TILE_SIZE);
                if (!options.transparent()) {
                    BufferedImage opaque = new BufferedImage(ImageryTileOptions.TILE_SIZE,
                            ImageryTileOptions.TILE_SIZE, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = opaque.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
                        graphics.drawImage(tile, 0, 0, null);
                    } finally {
                        graphics.dispose();
                    }
                    tile = opaque;
                }
                int storedY = options.tileProfile().storedY(
                        options.targetCrs().matrixHeight(metaTile.zoom()), y);
                Path tilePath = tileDirectory.resolve(
                        storedY + "." + options.outputFormat().extension());
                if (!ImageIO.write(tile, options.outputFormat().extension(), tilePath.toFile())) {
                    throw new IOException("当前 JVM 没有可用的影像编码器: "
                            + options.outputFormat().extension());
                }
                written++;
            }
        }
        return written;
    }

    private GeneralParameterValue[] readParameters(boolean useOverview) {
        ParameterValue<OverviewPolicy> overview =
                AbstractGridFormat.OVERVIEW_POLICY.createValue();
        // 选择最接近目标分辨率的金字塔层级，避免 QUALITY 固定读取更高分辨率后再缩小。
        overview.setValue(useOverview ? OverviewPolicy.NEAREST : OverviewPolicy.IGNORE);
        ParameterValue<Boolean> useImageN =
                AbstractGridFormat.USE_IMAGEN_IMAGEREAD.createValue();
        useImageN.setValue(Boolean.TRUE);
        return new GeneralParameterValue[]{overview, useImageN};
    }

    private Interpolation interpolation(ImageryTileOptions.Resampling resampling) {
        return Interpolation.getInstance(resampling == ImageryTileOptions.Resampling.NEAREST
                ? Interpolation.INTERP_NEAREST : Interpolation.INTERP_BILINEAR);
    }

    private MetaTileRange metaTileRange(int zoom, int minX, int maxX, int minY, int maxY,
            ImageryTileOptions.TargetCrs targetCrs) {
        ReferencedEnvelope upperLeft = tileEnvelope(zoom, minX, minY, targetCrs);
        ReferencedEnvelope lowerRight = tileEnvelope(zoom, maxX, maxY, targetCrs);
        ReferencedEnvelope bounds = new ReferencedEnvelope(
                upperLeft.getMinX(), lowerRight.getMaxX(), lowerRight.getMinY(),
                upperLeft.getMaxY(), coordinateReferenceSystem(targetCrs));
        return new MetaTileRange(zoom, minX, maxX, minY, maxY, bounds,
                (maxX - minX + 1) * ImageryTileOptions.TILE_SIZE,
                (maxY - minY + 1) * ImageryTileOptions.TILE_SIZE);
    }

    private void writeMetadata(Path output, String name, MosaicPlan plan,
            ImageryTileOptions options) throws IOException {
        ReferencedEnvelope bounds = plan.geographicBounds();
        double[] geographicBounds = {bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()};
        Map<String, Object> tileJson = new LinkedHashMap<>();
        tileJson.put("tilejson", "3.0.0");
        tileJson.put("name", name);
        tileJson.put("scheme", options.tileProfile().scheme());
        tileJson.put("crs", options.targetCrs().code());
        tileJson.put("tileMatrixSet", options.targetCrs().tileMatrixSet());
        tileJson.put("tiles", new String[]{"{z}/{x}/{y}." + options.outputFormat().extension()});
        tileJson.put("minzoom", plan.zooms().min());
        tileJson.put("maxzoom", plan.zooms().max());
        tileJson.put("bounds", geographicBounds);
        tileJson.put("format", options.outputFormat().extension());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("type", options.tileProfile().manifestType());
        manifest.put("sources", plan.sources().stream().map(source -> Map.of(
                "path", source.path().toString(), "sequenceNo", source.sequence() + 1,
                "sizeBytes", source.size(), "lastModified", source.modified().toString(),
                "nativeMaxZoom", source.nativeZoom(),
                "sourceNominalResolution", source.nominalResolution())).toList());
        manifest.put("overlapRule", "RESOLUTION_THEN_INPUT_ORDER");
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("targetCrs", options.targetCrs().code());
        manifest.put("tileMatrixSet", options.targetCrs().tileMatrixSet());
        manifest.put("tileProfile", options.tileProfile().name());
        manifest.put("format", options.outputFormat().name());
        manifest.put("resampling", options.resampling().name());
        manifest.put("transparent", options.transparent());
        manifest.put("minZoom", plan.zooms().min());
        manifest.put("maxZoom", plan.zooms().max());
        manifest.put("tileCount", plan.tileCount());
        manifest.put("bounds", geographicBounds);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("manifest.json").toFile(), manifest);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("tilejson.json").toFile(), tileJson);
    }

    private int calculateMinimumZoom(ReferencedEnvelope bounds,
            ImageryTileOptions.TargetCrs targetCrs) {
        double minimumExtent = Math.min(bounds.getWidth(), bounds.getHeight());
        double levelZeroSpan = worldHeight(targetCrs);
        for (int zoom = 0; zoom < ImageryTileOptions.MAX_ZOOM; zoom++) {
            double visiblePixels = minimumExtent * ImageryTileOptions.TILE_SIZE
                    * (1L << zoom) / levelZeroSpan;
            if (visiblePixels >= MIN_VISIBLE_PIXELS) {
                return zoom;
            }
        }
        return ImageryTileOptions.MAX_ZOOM;
    }

    private TileRange tileRange(ReferencedEnvelope bounds, int zoom,
            ImageryTileOptions.TargetCrs targetCrs) {
        int matrixWidth = targetCrs.matrixWidth(zoom);
        int matrixHeight = targetCrs.matrixHeight(zoom);
        double minWorldX = worldMinX(targetCrs);
        double maxWorldY = worldMaxY(targetCrs);
        int minX = clampTile((int) Math.floor(snapTileCoordinate((bounds.getMinX() - minWorldX)
                / worldWidth(targetCrs) * matrixWidth)), matrixWidth);
        int maxX = clampTile((int) Math.ceil(snapTileCoordinate((bounds.getMaxX() - minWorldX)
                / worldWidth(targetCrs) * matrixWidth)) - 1, matrixWidth);
        int minY = clampTile((int) Math.floor(snapTileCoordinate((maxWorldY - bounds.getMaxY())
                / worldHeight(targetCrs) * matrixHeight)), matrixHeight);
        int maxY = clampTile((int) Math.ceil(snapTileCoordinate((maxWorldY - bounds.getMinY())
                / worldHeight(targetCrs) * matrixHeight)) - 1, matrixHeight);
        return new TileRange(minX, maxX, minY, maxY);
    }

    private double snapTileCoordinate(double value) {
        double integer = Math.rint(value);
        return Math.abs(value - integer) <= 1e-7 ? integer : value;
    }

    private ReferencedEnvelope tileEnvelope(int zoom, int x, int y,
            ImageryTileOptions.TargetCrs targetCrs) {
        double tileWidth = worldWidth(targetCrs) / targetCrs.matrixWidth(zoom);
        double tileHeight = worldHeight(targetCrs) / targetCrs.matrixHeight(zoom);
        double minX = worldMinX(targetCrs) + x * tileWidth;
        double maxY = worldMaxY(targetCrs) - y * tileHeight;
        return new ReferencedEnvelope(minX, minX + tileWidth,
                maxY - tileHeight, maxY, coordinateReferenceSystem(targetCrs));
    }

    private ReferencedEnvelope clampTarget(ReferencedEnvelope bounds,
            ImageryTileOptions.TargetCrs targetCrs) {
        double minX = Math.max(worldMinX(targetCrs), bounds.getMinX());
        double maxX = Math.min(worldMaxX(targetCrs), bounds.getMaxX());
        double minY = Math.max(worldMinY(targetCrs), bounds.getMinY());
        double maxY = Math.min(worldMaxY(targetCrs), bounds.getMaxY());
        if (minX >= maxX || minY >= maxY) {
            throw new IllegalArgumentException("GeoTIFF 范围不在目标坐标系可切片区域内");
        }
        return new ReferencedEnvelope(minX, maxX, minY, maxY,
                coordinateReferenceSystem(targetCrs));
    }

    private CoordinateReferenceSystem coordinateReferenceSystem(
            ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326 ? WGS84 : WEB_MERCATOR;
    }

    private double worldMinX(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326
                ? -180d : -WEB_MERCATOR_LIMIT;
    }

    private double worldMaxX(ImageryTileOptions.TargetCrs targetCrs) {
        return -worldMinX(targetCrs);
    }

    private double worldMinY(ImageryTileOptions.TargetCrs targetCrs) {
        return targetCrs == ImageryTileOptions.TargetCrs.EPSG_4326
                ? -90d : -WEB_MERCATOR_LIMIT;
    }

    private double worldMaxY(ImageryTileOptions.TargetCrs targetCrs) {
        return -worldMinY(targetCrs);
    }

    private double worldWidth(ImageryTileOptions.TargetCrs targetCrs) {
        return worldMaxX(targetCrs) - worldMinX(targetCrs);
    }

    private double worldHeight(ImageryTileOptions.TargetCrs targetCrs) {
        return worldMaxY(targetCrs) - worldMinY(targetCrs);
    }

    private CoordinateReferenceSystem canonicalSourceCrs(
            CoordinateReferenceSystem sourceCrs) {
        if (sourceCrs == null) {
            throw new IllegalArgumentException("GeoTIFF 缺少坐标参考系");
        }
        try {
            Integer epsgCode = CRS.lookupEpsgCode(sourceCrs, true);
            if (Integer.valueOf(4326).equals(epsgCode)) {
                return WGS84;
            }
            if (Integer.valueOf(3857).equals(epsgCode)) {
                return WEB_MERCATOR;
            }
        } catch (Exception ignored) {
            // 无法识别权威编码时继续使用结构等价判断。
        }
        if (CRS.equalsIgnoreMetadata(sourceCrs, WGS84)) {
            return WGS84;
        }
        if (CRS.equalsIgnoreMetadata(sourceCrs, WEB_MERCATOR)) {
            return WEB_MERCATOR;
        }
        String identifier;
        try {
            identifier = CRS.lookupIdentifier(sourceCrs, true);
        } catch (Exception ex) {
            identifier = sourceCrs.getName().toString();
        }
        throw new IllegalArgumentException("首期影像切片仅支持 EPSG:4326 或 EPSG:3857，当前: "
                + identifier);
    }

    private ReferencedEnvelope normalizeSourceBounds(ReferencedEnvelope bounds,
            CoordinateReferenceSystem sourceCrs) {
        if (!Double.isFinite(bounds.getMinX()) || !Double.isFinite(bounds.getMaxX())
                || !Double.isFinite(bounds.getMinY()) || !Double.isFinite(bounds.getMaxY())
                || bounds.isEmpty()) {
            throw new IllegalArgumentException("GeoTIFF 空间范围无效");
        }
        boolean geographic = CRS.equalsIgnoreMetadata(sourceCrs, WGS84);
        double minX = geographic ? -180d : -WEB_MERCATOR_LIMIT;
        double maxX = geographic ? 180d : WEB_MERCATOR_LIMIT;
        double minY = geographic ? -90d : -WEB_MERCATOR_LIMIT;
        double maxY = geographic ? 90d : WEB_MERCATOR_LIMIT;
        double tolerance = geographic ? GEOGRAPHIC_BOUNDARY_TOLERANCE : 1e-3;
        if (bounds.getMinX() < minX - tolerance || bounds.getMaxX() > maxX + tolerance
                || bounds.getMinY() < minY - tolerance || bounds.getMaxY() > maxY + tolerance) {
            throw new IllegalArgumentException("GeoTIFF 范围超出源坐标系的有效切片区域；"
                    + "当前不支持跨日期变更线或0到360度经度范围");
        }
        if (!geographic) {
            return bounds;
        }
        return new ReferencedEnvelope(
                snapGeographicBoundary(bounds.getMinX(), minX, maxX),
                snapGeographicBoundary(bounds.getMaxX(), minX, maxX),
                snapGeographicBoundary(bounds.getMinY(), minY, maxY),
                snapGeographicBoundary(bounds.getMaxY(), minY, maxY),
                sourceCrs);
    }

    private double snapGeographicBoundary(double value, double minimum, double maximum) {
        if (value < minimum) {
            return minimum;
        }
        if (value > maximum) {
            return maximum;
        }
        return value;
    }

    private int clampTile(int value, int dimension) {
        return Math.max(0, Math.min(dimension - 1, value));
    }

    private static CoordinateReferenceSystem decode(String code) {
        try {
            return CRS.decode(code, true);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final class PartReaderCache implements AutoCloseable {
        private final int capacity;
        private final LinkedHashMap<Path, GeoTiffReader> readers =
                new LinkedHashMap<>(16, 0.75f, true);

        private PartReaderCache(int capacity) {
            this.capacity = capacity;
        }

        private GeoTiffReader reader(Path path) throws IOException {
            GeoTiffReader existing = readers.get(path);
            if (existing != null) {
                return existing;
            }
            GeoTiffReader created = new GeoTiffReader(path.toFile());
            readers.put(path, created);
            if (readers.size() > capacity) {
                Map.Entry<Path, GeoTiffReader> eldest = readers.entrySet().iterator().next();
                readers.remove(eldest.getKey());
                eldest.getValue().dispose();
            }
            return created;
        }

        @Override
        public void close() {
            for (GeoTiffReader reader : readers.values()) {
                reader.dispose();
            }
            readers.clear();
        }
    }

    private static final class PartImageCache implements AutoCloseable {
        private final int capacity;
        private final LinkedHashMap<PartImageKey, BufferedImage> images =
                new LinkedHashMap<>(8, 0.75f, true);

        private PartImageCache(int capacity) {
            this.capacity = capacity;
        }

        private BufferedImage image(GeoTiffPreprocessor.PreparedPart part,
                int desiredWidth, int desiredHeight) throws IOException {
            int level = overviewLevel(part, desiredWidth, desiredHeight);
            PartImageKey key = new PartImageKey(part.path(), level);
            BufferedImage existing = images.get(key);
            if (existing != null) {
                return existing;
            }
            BufferedImage loaded = level == 0
                    ? ImageIO.read(part.path().toFile())
                    : readOverview(part.path(), level - 1);
            if (loaded == null) {
                throw new IOException("无法读取影像预处理分块: " + part.path());
            }
            images.put(key, loaded);
            if (images.size() > capacity) {
                Map.Entry<PartImageKey, BufferedImage> eldest =
                        images.entrySet().iterator().next();
                images.remove(eldest.getKey());
                eldest.getValue().flush();
            }
            return loaded;
        }

        private int overviewLevel(GeoTiffPreprocessor.PreparedPart part,
                int desiredWidth, int desiredHeight) {
            double ratio = Math.max((double) part.width() / desiredWidth,
                    (double) part.height() / desiredHeight);
            if (ratio <= 1.0 || part.overviewCount() == 0) {
                return 0;
            }
            int nearest = (int) Math.round(Math.log(ratio) / Math.log(2.0));
            return Math.max(0, Math.min(part.overviewCount(), nearest));
        }

        private BufferedImage readOverview(Path base, int imageIndex) throws IOException {
            Path overview = base.resolveSibling(base.getFileName() + ".ovr");
            try (ImageInputStream input = ImageIO.createImageInputStream(overview.toFile())) {
                if (input == null) {
                    throw new IOException("无法打开影像分块概览: " + overview);
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new IOException("没有可用的 TIFF overview 读取器: " + overview);
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    return reader.read(imageIndex);
                } finally {
                    reader.dispose();
                }
            }
        }

        @Override
        public void close() {
            for (BufferedImage image : images.values()) {
                image.flush();
            }
            images.clear();
        }
    }

    private record PartImageKey(Path path, int level) { }

    public record MosaicPlan(List<SourceRaster> sources, ReferencedEnvelope geographicBounds,
            ZoomRange zooms, List<PlannedTile> tiles, long tileCount) { }

    public record SourceRaster(Path path, ReferencedEnvelope bounds, int nativeZoom,
            double nominalResolution, double pixelArea, int sequence, long size, FileTime modified) {
        public void validateUnchanged() throws IOException {
            if (Files.size(path) != size || !Files.getLastModifiedTime(path).equals(modified)) {
                throw new IllegalStateException("处理期间源影像发生变化: " + path);
            }
        }
    }

    public record PlannedTile(MetaTileRange range, int mask) { }

    private record IndexedPart(GeoTiffPreprocessor.PreparedPart part, SourceRaster source, boolean direct) { }

    public record ZoomRange(int min, int max) { }

    private record TileRange(int minX, int maxX, int minY, int maxY) { }

    private record RasterSource(ReferencedEnvelope bounds, int width, int height,
            double resolutionX, double resolutionY) { }

    public record MetaTileRange(
            int zoom,
            int minX,
            int maxX,
            int minY,
            int maxY,
            ReferencedEnvelope bounds,
            int pixelWidth,
            int pixelHeight) { }
}
