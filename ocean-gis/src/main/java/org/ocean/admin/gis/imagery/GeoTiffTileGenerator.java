package org.ocean.admin.gis.imagery;

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
import org.ocean.admin.gis.processing.GisProcessingProgress;

import javax.imageio.ImageIO;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** 使用 GeoTools、Eclipse ImageN 与 ImageIO 将普通 GeoTIFF 生成为静态影像瓦片集。 */
public class GeoTiffTileGenerator {

    private static final double WEB_MERCATOR_LIMIT = 20_037_508.342789244;
    private static final int META_TILE_SIZE = 4;
    private static final int MIN_VISIBLE_PIXELS = 1;
    private static final long PROGRESS_REPORT_INTERVAL_NANOS = 2_000_000_000L;
    private static final CoordinateReferenceSystem WEB_MERCATOR = decode("EPSG:3857");
    private static final CoordinateReferenceSystem WGS84 = decode("EPSG:4326");

    private final ObjectMapper objectMapper;

    public GeoTiffTileGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void generate(Path input, Path output, ImageryTileOptions options,
            Consumer<GisProcessingProgress> progressListener) {
        generate(input, input, output, options, progressListener);
    }

    public void generate(Path input, Path metadataSource, Path output, ImageryTileOptions options,
            Consumer<GisProcessingProgress> progressListener) {
        progressListener.accept(GisProcessingProgress.indeterminate(
                "analyzing", "正在分析影像范围和切片层级"));
        Path source = input.toAbsolutePath().normalize();
        Path originalSource = metadataSource.toAbsolutePath().normalize();
        Path target = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException("GeoTIFF 文件不存在或不可读: " + input);
        }
        try {
            Files.createDirectories(target);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建影像瓦片输出目录: " + target, ex);
        }

        GeoTiffReader reader = null;
        try {
            reader = new GeoTiffReader(source.toFile());
            RasterSource rasterSource = inspect(reader);
            CoordinateReferenceSystem targetCrs = coordinateReferenceSystem(options.targetCrs());
            ReferencedEnvelope targetBounds = clampTarget(rasterSource.bounds().transform(
                    targetCrs, true), options.targetCrs());
            ReferencedEnvelope geographicBounds = rasterSource.bounds().transform(WGS84, true);
            ZoomRange zooms = resolveZooms(rasterSource, targetBounds, options);
            long tileCount = countTiles(targetBounds, zooms, options.targetCrs());
            if (tileCount > ImageryTileOptions.MAX_TILE_COUNT) {
                throw new IllegalArgumentException("预计生成瓦片" + tileCount
                        + "张，超过单任务上限" + ImageryTileOptions.MAX_TILE_COUNT + "张");
            }
            progressListener.accept(GisProcessingProgress.determinate(
                    "generating", 0, tileCount,
                    "影像切片工作量已确定，共" + tileCount + "张瓦片"));

            RasterSymbolizer symbolizer = new StyleBuilder().createRasterSymbolizer();
            Interpolation interpolation = interpolation(options.resampling());

            long completed = 0;
            int lastReportedPercent = 0;
            long lastReportNanos = System.nanoTime();
            for (int zoom = zooms.min(); zoom <= zooms.max(); zoom++) {
                TileRange range = tileRange(targetBounds, zoom, options.targetCrs());
                for (int x = range.minX(); x <= range.maxX(); x += META_TILE_SIZE) {
                    int maxX = Math.min(range.maxX(), x + META_TILE_SIZE - 1);
                    for (int y = range.minY(); y <= range.maxY(); y += META_TILE_SIZE) {
                        int maxY = Math.min(range.maxY(), y + META_TILE_SIZE - 1);
                        MetaTileRange metaTile = metaTileRange(
                                zoom, x, maxX, y, maxY, options.targetCrs());
                        try {
                            BufferedImage rendered = renderMetaTile(
                                    reader, symbolizer, interpolation, metaTile, options);
                            completed += splitAndWrite(rendered, metaTile, target, options);
                        } catch (Exception ex) {
                            throw new IllegalStateException("影像元瓦片处理失败: z=" + zoom
                                    + ", x=" + x + "-" + maxX
                                    + ", y=" + y + "-" + maxY, ex);
                        }
                        int currentPercent = processingPercent(completed, tileCount);
                        long currentNanos = System.nanoTime();
                        if (currentPercent > lastReportedPercent
                                || currentNanos - lastReportNanos
                                >= PROGRESS_REPORT_INTERVAL_NANOS) {
                            lastReportedPercent = currentPercent;
                            lastReportNanos = currentNanos;
                            progressListener.accept(GisProcessingProgress.determinate(
                                    "generating", completed, tileCount,
                                    "正在生成影像瓦片："
                                            + completed + "/" + tileCount + "张"));
                        }
                    }
                }
            }
            progressListener.accept(GisProcessingProgress.determinate(
                    "finalizing", completed, tileCount, "正在写入影像切片元数据"));
            writeMetadata(target, originalSource, geographicBounds, zooms, options, tileCount);
        } catch (IOException ex) {
            throw new IllegalStateException("GeoTIFF 读取或瓦片写入失败: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
                throw (RuntimeException) ex;
            }
            throw new IllegalStateException("GeoTIFF 影像切片失败: " + ex.getMessage(), ex);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
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
        ReferencedEnvelope sourceBounds = new ReferencedEnvelope(reader.getOriginalEnvelope())
                .transform(canonicalSourceCrs, true);
        validateSourceBounds(sourceBounds, canonicalSourceCrs);
        return new RasterSource(sourceBounds, gridRange.getSpan(0), gridRange.getSpan(1));
    }

    private BufferedImage renderMetaTile(GeoTiffReader reader,
            RasterSymbolizer symbolizer, Interpolation interpolation,
            MetaTileRange metaTile, ImageryTileOptions options) throws Exception {
        int imageType = options.transparent()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage rendered = new BufferedImage(
                metaTile.pixelWidth(), metaTile.pixelHeight(), imageType);
        Graphics2D graphics = rendered.createGraphics();
        try {
            graphics.setColor(options.transparent() ? new Color(0, 0, 0, 0) : Color.WHITE);
            graphics.fillRect(0, 0, rendered.getWidth(), rendered.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    options.resampling() == ImageryTileOptions.Resampling.NEAREST
                            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Rectangle rasterArea = new Rectangle(rendered.getWidth(), rendered.getHeight());
            AffineTransform worldToScreen = RendererUtilities.worldToScreenTransform(
                    metaTile.bounds(), rasterArea);
            RenderingHints rendererHints = new RenderingHints(
                    ImageN.KEY_INTERPOLATION, interpolation);
            GridCoverageRenderer renderer = new GridCoverageRenderer(
                    coordinateReferenceSystem(options.targetCrs()), metaTile.bounds(),
                    rasterArea, worldToScreen, rendererHints);
            // 输入范围已限制在单一合法投影域，避免全球包裹处理带来的额外采样与越界经度。
            renderer.setAdvancedProjectionHandlingEnabled(false);
            renderer.setWrapEnabled(false);
            renderer.paint(graphics, reader, readParameters(), symbolizer, interpolation,
                    options.transparent() ? null : Color.WHITE);
        } finally {
            graphics.dispose();
        }
        return rendered;
    }

    private int splitAndWrite(BufferedImage rendered, MetaTileRange metaTile,
            Path output, ImageryTileOptions options) throws IOException {
        int written = 0;
        for (int x = metaTile.minX(); x <= metaTile.maxX(); x++) {
            Path tileDirectory = output.resolve(Integer.toString(metaTile.zoom()))
                    .resolve(Integer.toString(x));
            Files.createDirectories(tileDirectory);
            for (int y = metaTile.minY(); y <= metaTile.maxY(); y++) {
                int sourceX = (x - metaTile.minX()) * ImageryTileOptions.TILE_SIZE;
                int sourceY = (y - metaTile.minY()) * ImageryTileOptions.TILE_SIZE;
                BufferedImage tile = rendered.getSubimage(sourceX, sourceY,
                        ImageryTileOptions.TILE_SIZE, ImageryTileOptions.TILE_SIZE);
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

    private GeneralParameterValue[] readParameters() {
        ParameterValue<OverviewPolicy> overview =
                AbstractGridFormat.OVERVIEW_POLICY.createValue();
        // 选择最接近目标分辨率的金字塔层级，避免 QUALITY 固定读取更高分辨率后再缩小。
        overview.setValue(OverviewPolicy.NEAREST);
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

    private void writeMetadata(Path output, Path source, ReferencedEnvelope bounds,
            ZoomRange zooms, ImageryTileOptions options, long tileCount) throws IOException {
        double[] geographicBounds = {bounds.getMinX(), bounds.getMinY(),
                bounds.getMaxX(), bounds.getMaxY()};
        Map<String, Object> tileJson = new LinkedHashMap<>();
        tileJson.put("tilejson", "3.0.0");
        tileJson.put("name", fileName(source));
        tileJson.put("scheme", options.tileProfile().scheme());
        tileJson.put("crs", options.targetCrs().code());
        tileJson.put("tileMatrixSet", options.targetCrs().tileMatrixSet());
        tileJson.put("tiles", new String[]{"{z}/{x}/{y}."
                + options.outputFormat().extension()});
        tileJson.put("minzoom", zooms.min());
        tileJson.put("maxzoom", zooms.max());
        tileJson.put("bounds", geographicBounds);
        tileJson.put("format", options.outputFormat().extension());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("tilejson.json").toFile(), tileJson);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("type", options.tileProfile().manifestType());
        manifest.put("source", source.toString());
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("targetCrs", options.targetCrs().code());
        manifest.put("tileMatrixSet", options.targetCrs().tileMatrixSet());
        manifest.put("tileProfile", options.tileProfile().name());
        manifest.put("format", options.outputFormat().name());
        manifest.put("resampling", options.resampling().name());
        manifest.put("transparent", options.transparent());
        manifest.put("minZoom", zooms.min());
        manifest.put("maxZoom", zooms.max());
        manifest.put("tileCount", tileCount);
        manifest.put("bounds", geographicBounds);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("manifest.json").toFile(), manifest);
    }

    private ZoomRange resolveZooms(RasterSource source, ReferencedEnvelope bounds,
            ImageryTileOptions options) {
        double sourceResolution = Math.max(
                bounds.getWidth() / source.width(), bounds.getHeight() / source.height());
        double levelZeroSpan = worldHeight(options.targetCrs());
        int nativeZoom = (int) Math.floor(Math.log(levelZeroSpan
                / (ImageryTileOptions.TILE_SIZE * sourceResolution)) / Math.log(2));
        nativeZoom = Math.max(0, Math.min(ImageryTileOptions.MAX_ZOOM, nativeZoom));
        int max = options.maxZoom() == null ? nativeZoom : options.maxZoom();
        int min = options.minZoom() == null
                ? Math.min(max, calculateMinimumZoom(bounds, options.targetCrs()))
                : options.minZoom();
        if (min > max) {
            throw new IllegalArgumentException("影像最小层级" + min
                    + "不能大于按源分辨率确定的最大层级" + max
                    + "；请显式设置不小于最小层级的 maxZoom");
        }
        return new ZoomRange(min, max);
    }

    private int processingPercent(long completed, long total) {
        return (int) Math.min(99L, completed * 100L / total);
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

    private long countTiles(ReferencedEnvelope bounds, ZoomRange zooms,
            ImageryTileOptions.TargetCrs targetCrs) {
        long count = 0;
        for (int zoom = zooms.min(); zoom <= zooms.max(); zoom++) {
            TileRange range = tileRange(bounds, zoom, targetCrs);
            count = Math.addExact(count, (long) (range.maxX() - range.minX() + 1)
                    * (range.maxY() - range.minY() + 1));
        }
        return count;
    }

    private TileRange tileRange(ReferencedEnvelope bounds, int zoom,
            ImageryTileOptions.TargetCrs targetCrs) {
        int matrixWidth = targetCrs.matrixWidth(zoom);
        int matrixHeight = targetCrs.matrixHeight(zoom);
        double minWorldX = worldMinX(targetCrs);
        double maxWorldY = worldMaxY(targetCrs);
        int minX = clampTile((int) Math.floor((bounds.getMinX() - minWorldX)
                / worldWidth(targetCrs) * matrixWidth), matrixWidth);
        int maxX = clampTile((int) Math.floor((Math.nextDown(bounds.getMaxX()) - minWorldX)
                / worldWidth(targetCrs) * matrixWidth), matrixWidth);
        int minY = clampTile((int) Math.floor((maxWorldY
                - Math.nextDown(bounds.getMaxY()))
                / worldHeight(targetCrs) * matrixHeight), matrixHeight);
        int maxY = clampTile((int) Math.floor((maxWorldY - bounds.getMinY())
                / worldHeight(targetCrs) * matrixHeight), matrixHeight);
        return new TileRange(minX, maxX, minY, maxY);
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

    private void validateSourceBounds(ReferencedEnvelope bounds,
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
        double tolerance = geographic ? 1e-9 : 1e-3;
        if (bounds.getMinX() < minX - tolerance || bounds.getMaxX() > maxX + tolerance
                || bounds.getMinY() < minY - tolerance || bounds.getMaxY() > maxY + tolerance) {
            throw new IllegalArgumentException("GeoTIFF 范围超出源坐标系的有效切片区域；"
                    + "当前不支持跨日期变更线或0到360度经度范围");
        }
    }

    private int clampTile(int value, int dimension) {
        return Math.max(0, Math.min(dimension - 1, value));
    }

    private String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static CoordinateReferenceSystem decode(String code) {
        try {
            return CRS.decode(code, true);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private record ZoomRange(int min, int max) { }

    private record TileRange(int minX, int maxX, int minY, int maxY) { }

    private record RasterSource(ReferencedEnvelope bounds, int width, int height) { }

    private record MetaTileRange(
            int zoom,
            int minX,
            int maxX,
            int minY,
            int maxY,
            ReferencedEnvelope bounds,
            int pixelWidth,
            int pixelHeight) { }
}
