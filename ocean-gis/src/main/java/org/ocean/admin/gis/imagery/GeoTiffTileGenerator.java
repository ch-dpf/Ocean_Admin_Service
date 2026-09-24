package org.ocean.admin.gis.imagery;

import org.eclipse.imagen.Interpolation;
import org.eclipse.imagen.RenderedOp;
import org.eclipse.imagen.media.scale.ScaleDescriptor;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.style.Style;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.GridCoverageLayer;
import org.geotools.map.MapContent;
import org.geotools.referencing.CRS;
import org.geotools.renderer.RenderListener;
import org.geotools.renderer.lite.StreamingRenderer;
import org.geotools.styling.StyleBuilder;
import org.ocean.admin.gis.processing.GisProcessingProgress;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** 使用 GeoTools、Eclipse ImageN 与 ImageIO 将普通 GeoTIFF 生成为 XYZ 静态瓦片集。 */
public class GeoTiffTileGenerator {

    private static final double WEB_MERCATOR_LIMIT = 20_037_508.342789244;
    private static final int RENDER_SCALE = 2;
    private static final int MIN_VISIBLE_PIXELS = 1;
    private static final CoordinateReferenceSystem WEB_MERCATOR = decode("EPSG:3857");
    private static final CoordinateReferenceSystem WGS84 = decode("EPSG:4326");

    private final ObjectMapper objectMapper;

    public GeoTiffTileGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void generate(Path input, Path output, ImageryTileOptions options,
            Consumer<GisProcessingProgress> progressListener) {
        progressListener.accept(GisProcessingProgress.indeterminate(
                "analyzing", "正在分析影像范围和切片层级"));
        Path source = input.toAbsolutePath().normalize();
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
        ImageInputStream inputStream = null;
        GridCoverage2D coverage = null;
        MapContent map = new MapContent();
        try {
            inputStream = ImageIO.createImageInputStream(source.toFile());
            if (inputStream == null) {
                throw new IllegalArgumentException("无法打开 GeoTIFF 输入流: " + source);
            }
            reader = new GeoTiffReader(inputStream);
            coverage = reader.read(
                    new org.geotools.api.parameter.GeneralParameterValue[0]);
            CoordinateReferenceSystem sourceCrs = coverage.getCoordinateReferenceSystem2D();
            validateSourceCrs(sourceCrs);
            validateCoverage(coverage);

            ReferencedEnvelope sourceBounds = new ReferencedEnvelope(coverage.getEnvelope2D());
            ReferencedEnvelope mercatorBounds = clampMercator(sourceBounds.transform(
                    WEB_MERCATOR, true));
            ReferencedEnvelope geographicBounds = sourceBounds.transform(WGS84, true);
            ZoomRange zooms = resolveZooms(coverage, mercatorBounds, options);
            long tileCount = countTiles(mercatorBounds, zooms);
            if (tileCount > ImageryTileOptions.MAX_TILE_COUNT) {
                throw new IllegalArgumentException("预计生成瓦片" + tileCount
                        + "张，超过单任务上限" + ImageryTileOptions.MAX_TILE_COUNT + "张");
            }
            progressListener.accept(GisProcessingProgress.determinate(
                    "generating", 0, tileCount,
                    "影像切片工作量已确定，共" + tileCount + "张瓦片"));

            Style style = new StyleBuilder().createStyle(
                    new StyleBuilder().createRasterSymbolizer());
            map.addLayer(new GridCoverageLayer(coverage, style));
            StreamingRenderer renderer = new StreamingRenderer();
            renderer.setMapContent(map);

            long completed = 0;
            int lastProgress = 0;
            for (int zoom = zooms.min(); zoom <= zooms.max(); zoom++) {
                TileRange range = tileRange(mercatorBounds, zoom);
                for (int x = range.minX(); x <= range.maxX(); x++) {
                    for (int y = range.minY(); y <= range.maxY(); y++) {
                        writeTile(renderer, target, mercatorBounds, zoom, x, y, options);
                        completed++;
                        int progress = processingProgress(completed, tileCount);
                        if (progress > lastProgress) {
                            lastProgress = progress;
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
            writeMetadata(target, source, geographicBounds, zooms, options, tileCount);
        } catch (IOException ex) {
            throw new IllegalStateException("GeoTIFF 读取或瓦片写入失败: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
                throw (RuntimeException) ex;
            }
            throw new IllegalStateException("GeoTIFF 影像切片失败: " + ex.getMessage(), ex);
        } finally {
            map.dispose();
            if (coverage != null) {
                coverage.dispose(true);
            }
            if (reader != null) {
                reader.dispose();
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                    // 主处理结果优先；流关闭失败不覆盖原始异常。
                }
            }
        }
    }

    private int processingProgress(long completed, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(99L, completed * 100L / total);
    }

    private void writeTile(StreamingRenderer renderer, Path output,
            ReferencedEnvelope coverageBounds, int zoom, int x, int y,
            ImageryTileOptions options) throws IOException {
        int renderSize = ImageryTileOptions.TILE_SIZE * RENDER_SCALE;
        int imageType = options.transparent()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage rendered = new BufferedImage(renderSize, renderSize, imageType);
        Graphics2D graphics = rendered.createGraphics();
        try {
            graphics.setColor(options.transparent() ? new Color(0, 0, 0, 0) : Color.WHITE);
            graphics.fillRect(0, 0, renderSize, renderSize);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            ReferencedEnvelope tileBounds = tileEnvelope(zoom, x, y);
            ReferencedEnvelope renderBounds = intersection(tileBounds, coverageBounds);
            if (renderBounds != null) {
                Rectangle renderArea = renderArea(tileBounds, renderBounds, renderSize);
                renderCoverage(renderer, graphics, renderBounds, renderArea, imageType,
                        options.transparent(), zoom, x, y);
            }
        } finally {
            graphics.dispose();
        }

        int interpolation = options.resampling() == ImageryTileOptions.Resampling.NEAREST
                ? Interpolation.INTERP_NEAREST : Interpolation.INTERP_BILINEAR;
        RenderedOp scaled = ScaleDescriptor.create(rendered, 1f / RENDER_SCALE,
                1f / RENDER_SCALE, 0f, 0f, Interpolation.getInstance(interpolation), null);
        try {
            BufferedImage tile = new BufferedImage(ImageryTileOptions.TILE_SIZE,
                    ImageryTileOptions.TILE_SIZE, imageType);
            Graphics2D tileGraphics = tile.createGraphics();
            try {
                if (!options.transparent()) {
                    tileGraphics.setColor(Color.WHITE);
                    tileGraphics.fillRect(0, 0, tile.getWidth(), tile.getHeight());
                }
                tileGraphics.drawRenderedImage(scaled, new AffineTransform());
            } finally {
                tileGraphics.dispose();
            }
            Path tilePath = output.resolve(Integer.toString(zoom))
                    .resolve(Integer.toString(x))
                    .resolve(y + "." + options.outputFormat().extension());
            Files.createDirectories(tilePath.getParent());
            if (!ImageIO.write(tile, options.outputFormat().extension(), tilePath.toFile())) {
                throw new IOException("当前 JVM 没有可用的影像编码器: "
                        + options.outputFormat().extension());
            }
        } finally {
            scaled.dispose();
        }
    }

    private void renderCoverage(StreamingRenderer renderer, Graphics2D tileGraphics,
            ReferencedEnvelope renderBounds, Rectangle renderArea, int imageType,
            boolean transparent, int zoom, int x, int y) throws IOException {
        BufferedImage patch = new BufferedImage(renderArea.width, renderArea.height, imageType);
        Graphics2D graphics = patch.createGraphics();
        AtomicReference<Exception> renderingError = new AtomicReference<>();
        RenderListener listener = new RenderListener() {
            @Override
            public void featureRenderer(SimpleFeature feature) {
                // 栅格渲染不产生矢量要素事件。
            }

            @Override
            public void errorOccurred(Exception exception) {
                renderingError.compareAndSet(null, exception);
            }
        };
        renderer.addRenderListener(listener);
        try {
            graphics.setColor(transparent ? new Color(0, 0, 0, 0) : Color.WHITE);
            graphics.fillRect(0, 0, patch.getWidth(), patch.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            renderer.paint(graphics, new Rectangle(patch.getWidth(), patch.getHeight()),
                    renderBounds);
        } finally {
            renderer.removeRenderListener(listener);
            graphics.dispose();
        }
        Exception error = renderingError.get();
        if (error != null) {
            throw new IOException("影像瓦片渲染失败: z=" + zoom + ", x=" + x + ", y=" + y,
                    error);
        }
        tileGraphics.drawImage(patch, renderArea.x, renderArea.y, null);
    }

    private ReferencedEnvelope intersection(ReferencedEnvelope tileBounds,
            ReferencedEnvelope coverageBounds) {
        double minX = Math.max(tileBounds.getMinX(), coverageBounds.getMinX());
        double maxX = Math.min(tileBounds.getMaxX(), coverageBounds.getMaxX());
        double minY = Math.max(tileBounds.getMinY(), coverageBounds.getMinY());
        double maxY = Math.min(tileBounds.getMaxY(), coverageBounds.getMaxY());
        if (minX >= maxX || minY >= maxY) {
            return null;
        }
        return new ReferencedEnvelope(minX, maxX, minY, maxY, WEB_MERCATOR);
    }

    private Rectangle renderArea(ReferencedEnvelope tileBounds,
            ReferencedEnvelope renderBounds, int renderSize) {
        double scaleX = renderSize / tileBounds.getWidth();
        double scaleY = renderSize / tileBounds.getHeight();
        int minX = Math.min(renderSize - 1, clampPixel((int) Math.floor(
                (renderBounds.getMinX() - tileBounds.getMinX()) * scaleX), renderSize));
        int maxX = clampPixel((int) Math.ceil(
                (renderBounds.getMaxX() - tileBounds.getMinX()) * scaleX), renderSize);
        int minY = Math.min(renderSize - 1, clampPixel((int) Math.floor(
                (tileBounds.getMaxY() - renderBounds.getMaxY()) * scaleY), renderSize));
        int maxY = clampPixel((int) Math.ceil(
                (tileBounds.getMaxY() - renderBounds.getMinY()) * scaleY), renderSize);
        maxX = Math.max(minX + 1, maxX);
        maxY = Math.max(minY + 1, maxY);
        return new Rectangle(minX, minY, maxX - minX, maxY - minY);
    }

    private void writeMetadata(Path output, Path source, ReferencedEnvelope bounds,
            ZoomRange zooms, ImageryTileOptions options, long tileCount) throws IOException {
        double[] geographicBounds = {bounds.getMinX(), bounds.getMinY(),
                bounds.getMaxX(), bounds.getMaxY()};
        Map<String, Object> tileJson = new LinkedHashMap<>();
        tileJson.put("tilejson", "3.0.0");
        tileJson.put("name", fileName(source));
        tileJson.put("scheme", "xyz");
        tileJson.put("tiles", new String[]{"{z}/{x}/{y}."
                + options.outputFormat().extension()});
        tileJson.put("minzoom", zooms.min());
        tileJson.put("maxzoom", zooms.max());
        tileJson.put("bounds", geographicBounds);
        tileJson.put("format", options.outputFormat().extension());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("tilejson.json").toFile(), tileJson);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("type", "IMAGERY_XYZ");
        manifest.put("source", source.toString());
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("targetCrs", "EPSG:3857");
        manifest.put("tileProfile", "XYZ");
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

    private ZoomRange resolveZooms(GridCoverage2D coverage, ReferencedEnvelope bounds,
            ImageryTileOptions options) {
        int width = coverage.getRenderedImage().getWidth();
        int height = coverage.getRenderedImage().getHeight();
        double sourceResolution = Math.max(bounds.getWidth() / width, bounds.getHeight() / height);
        double worldWidth = WEB_MERCATOR_LIMIT * 2;
        int nativeZoom = (int) Math.floor(Math.log(worldWidth
                / (ImageryTileOptions.TILE_SIZE * sourceResolution)) / Math.log(2));
        nativeZoom = Math.max(0, Math.min(ImageryTileOptions.MAX_ZOOM, nativeZoom));
        int max = options.maxZoom() == null ? nativeZoom : options.maxZoom();
        int min = options.minZoom() == null
                ? Math.min(max, calculateMinimumZoom(bounds)) : options.minZoom();
        return new ZoomRange(min, max);
    }

    private int calculateMinimumZoom(ReferencedEnvelope bounds) {
        double minimumExtent = Math.min(bounds.getWidth(), bounds.getHeight());
        double worldWidth = WEB_MERCATOR_LIMIT * 2;
        for (int zoom = 0; zoom < ImageryTileOptions.MAX_ZOOM; zoom++) {
            double visiblePixels = minimumExtent * ImageryTileOptions.TILE_SIZE
                    * (1L << zoom) / worldWidth;
            if (visiblePixels >= MIN_VISIBLE_PIXELS) {
                return zoom;
            }
        }
        return ImageryTileOptions.MAX_ZOOM;
    }

    private long countTiles(ReferencedEnvelope bounds, ZoomRange zooms) {
        long count = 0;
        for (int zoom = zooms.min(); zoom <= zooms.max(); zoom++) {
            TileRange range = tileRange(bounds, zoom);
            count = Math.addExact(count, (long) (range.maxX() - range.minX() + 1)
                    * (range.maxY() - range.minY() + 1));
        }
        return count;
    }

    private TileRange tileRange(ReferencedEnvelope bounds, int zoom) {
        int dimension = 1 << zoom;
        int minX = clampTile((int) Math.floor((bounds.getMinX() + WEB_MERCATOR_LIMIT)
                / (2 * WEB_MERCATOR_LIMIT) * dimension), dimension);
        int maxX = clampTile((int) Math.floor((Math.nextDown(bounds.getMaxX())
                + WEB_MERCATOR_LIMIT) / (2 * WEB_MERCATOR_LIMIT) * dimension), dimension);
        int minY = clampTile((int) Math.floor((WEB_MERCATOR_LIMIT
                - Math.nextDown(bounds.getMaxY())) / (2 * WEB_MERCATOR_LIMIT) * dimension), dimension);
        int maxY = clampTile((int) Math.floor((WEB_MERCATOR_LIMIT - bounds.getMinY())
                / (2 * WEB_MERCATOR_LIMIT) * dimension), dimension);
        return new TileRange(minX, maxX, minY, maxY);
    }

    private ReferencedEnvelope tileEnvelope(int zoom, int x, int y) {
        double size = (2 * WEB_MERCATOR_LIMIT) / (1 << zoom);
        double minX = -WEB_MERCATOR_LIMIT + x * size;
        double maxY = WEB_MERCATOR_LIMIT - y * size;
        return new ReferencedEnvelope(minX, minX + size, maxY - size, maxY, WEB_MERCATOR);
    }

    private ReferencedEnvelope clampMercator(ReferencedEnvelope bounds) {
        double minX = Math.max(-WEB_MERCATOR_LIMIT, bounds.getMinX());
        double maxX = Math.min(WEB_MERCATOR_LIMIT, bounds.getMaxX());
        double minY = Math.max(-WEB_MERCATOR_LIMIT, bounds.getMinY());
        double maxY = Math.min(WEB_MERCATOR_LIMIT, bounds.getMaxY());
        if (minX >= maxX || minY >= maxY) {
            throw new IllegalArgumentException("GeoTIFF 范围不在 Web Mercator 可切片区域内");
        }
        return new ReferencedEnvelope(minX, maxX, minY, maxY, WEB_MERCATOR);
    }

    private void validateSourceCrs(CoordinateReferenceSystem sourceCrs) {
        if (sourceCrs == null) {
            throw new IllegalArgumentException("GeoTIFF 缺少坐标参考系");
        }
        if (!CRS.equalsIgnoreMetadata(sourceCrs, WGS84)
                && !CRS.equalsIgnoreMetadata(sourceCrs, WEB_MERCATOR)) {
            String identifier;
            try {
                identifier = CRS.lookupIdentifier(sourceCrs, true);
            } catch (Exception ex) {
                identifier = sourceCrs.getName().toString();
            }
            throw new IllegalArgumentException("首期影像切片仅支持 EPSG:4326 或 EPSG:3857，当前: "
                    + identifier);
        }
    }

    private void validateCoverage(GridCoverage2D coverage) {
        int bands = coverage.getRenderedImage().getSampleModel().getNumBands();
        if (bands < 1 || bands > 4) {
            throw new IllegalArgumentException("首期影像切片仅支持1到4个波段，当前: " + bands);
        }
        MathTransform gridToCrs = coverage.getGridGeometry().getGridToCRS2D();
        if (gridToCrs instanceof AffineTransform affine
                && (Math.abs(affine.getShearX()) > 1e-10
                || Math.abs(affine.getShearY()) > 1e-10)) {
            throw new IllegalArgumentException("首期影像切片不支持旋转或错切的 GeoTIFF 仿射变换");
        }
    }

    private int clampTile(int value, int dimension) {
        return Math.max(0, Math.min(dimension - 1, value));
    }

    private int clampPixel(int value, int renderSize) {
        return Math.max(0, Math.min(renderSize, value));
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
}
