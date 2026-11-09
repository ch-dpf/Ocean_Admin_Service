package org.ocean.admin.gis.vector;

import org.gdal.ogr.DataSource;
import org.gdal.ogr.ogr;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.FeatureLayer;
import org.geotools.map.MapContent;
import org.geotools.referencing.CRS;
import org.geotools.renderer.RenderListener;
import org.geotools.renderer.lite.StreamingRenderer;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.service.GeoPackageStore;
import org.ocean.admin.gis.util.VectorTileEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 固定快照的栅格配图：静态任务采用带缓冲的四乘四切片，WMS 按请求范围动态绘图。 */
public final class VectorRasterTileGenerator {
    private static final int TILE_SIZE = 256;
    private static final int META_SIZE = 4;
    private static final double WORLD = VectorTileEncoder.WORLD_SIZE;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private final ObjectMapper json;

    public VectorRasterTileGenerator(ObjectMapper json) { this.json = json; }

    /** 仅在 GDAL 隔离进程内运行，始终只读任务快照。 */
    public ObjectNode generate(JsonNode request) throws Exception {
        Path output = Path.of(request.path("output").asText()).toAbsolutePath().normalize();
        JsonNode definition = request.path("definition");
        JsonNode policy = definition.path("tilePolicy");
        var styles = new VectorRasterStyleBuilder(json, request.path("scheme"));
        Map<Long, String> groupKeys = new HashMap<>();
        Map<String, JsonNode> groupPolicies = new HashMap<>();
        for (JsonNode group : definition.path("groups")) groupKeys.put(group.path("id").asLong(), group.path("groupKey").asText());
        for (JsonNode group : policy.path("groups")) groupPolicies.put(group.path("groupKey").asText(), group);
        List<GisVectorLayer> layers = new ArrayList<>();
        for (JsonNode node : definition.path("layers")) {
            var layer = json.treeToValue(node, GisVectorLayer.class);
            if (styles.visibleLayer(layer.getId().toString(), groupKeys.getOrDefault(layer.getGroupId(), ""))) layers.add(layer);
        }
        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName("cartography");
        typeBuilder.setCRS(CRS.decode("EPSG:3857", true));
        typeBuilder.add("geometry", Geometry.class);
        typeBuilder.add("label", String.class);
        typeBuilder.setDefaultGeometry("geometry");
        SimpleFeatureType type = typeBuilder.buildFeatureType();
        int padding = Math.min(2048, Math.max(128, (int) Math.ceil(symbolPadding(request.path("scheme"))) + 8));
        long total = 0, completed = 0, outputBytes = 0;
        for (JsonNode range : request.path("ranges"))
            total += (long) (range.get(2).asInt() - range.get(1).asInt() + 1) * (range.get(4).asInt() - range.get(3).asInt() + 1);
        long lastReport = 0;
        DataSource data = ogr.Open("SQLite:" + request.path("input").asText(), 0);
        if (data == null) throw new IOException("栅格切片工作快照不可读取");
        try {
            for (JsonNode range : request.path("ranges")) {
                int z = range.get(0).asInt(), n = 1 << z;
                int minX = range.get(1).asInt(), maxX = range.get(2).asInt();
                int minY = range.get(3).asInt(), maxY = range.get(4).asInt();
                double tileSpan = WORLD / n, resolution = tileSpan / TILE_SIZE;
                // 固定全球分块原点，使相同层级的任务范围变化不会改变块内的标注布局。
                for (int x = minX / META_SIZE * META_SIZE; x <= maxX; x += META_SIZE) {
                    for (int y = minY / META_SIZE * META_SIZE; y <= maxY; y += META_SIZE) {
                        // 缓存只保留当前绘图块，避免大量逐要素样式在长任务中持续占用内存。
                        styles.clearStyles();
                        int columns = Math.min(META_SIZE, n - x), rows = Math.min(META_SIZE, n - y);
                        double west = -WORLD / 2 + x * tileSpan, north = WORLD / 2 - y * tileSpan;
                        var envelope = new ReferencedEnvelope(west - padding * resolution,
                                west + columns * tileSpan + padding * resolution,
                                north - rows * tileSpan - padding * resolution, north + padding * resolution,
                                type.getCoordinateReferenceSystem());
                        BufferedImage image = render(data, definition, layers, groupKeys, groupPolicies, styles,
                                type, z, envelope, envelope, resolution,
                                columns * TILE_SIZE + 2 * padding, rows * TILE_SIZE + 2 * padding,
                                System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
                        try {
                            for (int dx = 0; dx < columns; dx++) {
                                int tileX = x + dx;
                                if (tileX < minX || tileX > maxX) continue;
                                Path directory = output.resolve(Integer.toString(z)).resolve(Integer.toString(tileX));
                                Files.createDirectories(directory);
                                for (int dy = 0; dy < rows; dy++) {
                                    int tileY = y + dy;
                                    if (tileY < minY || tileY > maxY) continue;
                                    Path pending = directory.resolve(tileY + ".png.partial");
                                    BufferedImage tile = image.getSubimage(padding + dx * TILE_SIZE, padding + dy * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                                    // 小瓦片使用内存编码流，避免 GeoTools 文件流插件对每个 PNG 做随机磁盘写入。
                                    try (var file = Files.newOutputStream(pending, java.nio.file.StandardOpenOption.CREATE_NEW);
                                         var encoded = new javax.imageio.stream.MemoryCacheImageOutputStream(file)) {
                                        if (!ImageIO.write(tile, "PNG", encoded)) throw new IOException("PNG 编码器不可用");
                                    }
                                    outputBytes += Files.size(pending);
                                    if (outputBytes > request.path("maxOutputBytes").asLong())
                                        throw new IOException("栅格瓦片超过磁盘产物限额");
                                    Files.move(pending, directory.resolve(tileY + ".png"), StandardCopyOption.ATOMIC_MOVE);
                                    completed++;
                                }
                            }
                        } finally { image.flush(); }
                        long now = System.nanoTime();
                        if (now - lastReport > TimeUnit.SECONDS.toNanos(1) || completed == total) {
                            ObjectNode event = json.createObjectNode().put("event", "progress").put("code", "VECTOR_RASTER_TILES")
                                    .put("message", "生成栅格瓦片 " + completed + "/" + total)
                                    .put("completed", completed * 1000 / total);
                            System.out.println(json.writeValueAsString(event));
                            System.out.flush();
                            lastReport = now;
                        }
                    }
                }
            }
            return json.createObjectNode().put("tileCount", completed).put("outputBytes", outputBytes);
        } finally { data.delete(); }
    }

    /** WMS 使用任意范围和图片尺寸；查询坐标始终为工作包的 Web Mercator，绘图可重投影。 */
    public ObjectNode map(JsonNode request) throws Exception {
        int width = request.path("width").asInt(), height = request.path("height").asInt();
        if (width < 1 || height < 1 || width > 2048 || height > 2048)
            throw new IllegalArgumentException("地图图片尺寸超限");
        JsonNode definition = request.path("definition"), bbox = request.path("bounds");
        var targetCrs = CRS.decode(request.path("crs").asText(), true);
        var sourceCrs = CRS.decode("EPSG:3857", true);
        var envelope = new ReferencedEnvelope(bbox.get(0).asDouble(), bbox.get(2).asDouble(),
                bbox.get(1).asDouble(), bbox.get(3).asDouble(), targetCrs);
        boolean geographic = "EPSG:4326".equals(request.path("crs").asText());
        double south = geographic ? Math.max(-85.05112878, envelope.getMinY()) : envelope.getMinY();
        double north = geographic ? Math.min(85.05112878, envelope.getMaxY()) : envelope.getMaxY();
        BufferedImage image = null;
        if (south < north) {
            var query = new ReferencedEnvelope(envelope.getMinX(), envelope.getMaxX(), south, north, targetCrs)
                    .transform(sourceCrs, true, 20);
            double resolution = Math.max(query.getWidth() / width, query.getHeight() / height);
            double zoom = Math.max(0, Math.min(24, Math.log(WORLD / (TILE_SIZE * resolution)) / Math.log(2)));
            var styles = new VectorRasterStyleBuilder(json, request.path("scheme"));
            Map<Long, String> groups = new HashMap<>();
            Map<String, JsonNode> policies = new HashMap<>();
            for (JsonNode group : definition.path("groups")) groups.put(group.path("id").asLong(), group.path("groupKey").asText());
            for (JsonNode policy : definition.path("tilePolicy").path("groups")) policies.put(policy.path("groupKey").asText(), policy);
            List<GisVectorLayer> layers = new ArrayList<>();
            for (JsonNode node : definition.path("layers")) {
                var layer = json.treeToValue(node, GisVectorLayer.class);
                if (styles.visibleLayer(layer.getId().toString(), groups.getOrDefault(layer.getGroupId(), ""))) layers.add(layer);
            }
            SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
            builder.setName("cartography"); builder.setCRS(sourceCrs);
            builder.add("geometry", Geometry.class); builder.add("label", String.class); builder.setDefaultGeometry("geometry");
            query.expandBy(Math.min(256, Math.max(32, symbolPadding(request.path("scheme")) + 8)) * resolution);
            DataSource data = ogr.Open("SQLite:" + request.path("input").asText(), 0);
            if (data == null) throw new IOException("发布快照不可读取");
            try {
                image = render(data, definition, layers, groups, policies, styles, builder.buildFeatureType(),
                        zoom, envelope, query, resolution, width, height, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
            } finally { data.delete(); }
        }
        if (image == null) image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        try {
            if (!request.path("transparent").asBoolean()) {
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setComposite(java.awt.AlphaComposite.DstOver);
                    graphics.setColor(new java.awt.Color(request.path("background").asInt()));
                    graphics.fillRect(0, 0, width, height);
                } finally { graphics.dispose(); }
            }
            var bytes = new java.io.ByteArrayOutputStream();
            try (var encoded = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
                if (!ImageIO.write(image, "PNG", encoded)) throw new IOException("PNG 编码器不可用");
            }
            if (bytes.size() > 8 * 1024 * 1024) throw new IOException("地图图片超过传输限额");
            return json.createObjectNode().put("body", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } finally { image.flush(); }
    }

    private BufferedImage render(DataSource data, JsonNode definition, List<GisVectorLayer> layers,
                                 Map<Long, String> groupKeys, Map<String, JsonNode> policies, VectorRasterStyleBuilder styles,
                                 SimpleFeatureType type, double zoom, ReferencedEnvelope envelope, ReferencedEnvelope query,
                                 double resolution, int width, int height, long deadline) throws Exception {
        Map<VectorRasterStyleBuilder.Paint, ListFeatureCollection> batches = new LinkedHashMap<>();
        long[] coordinates = {0}, draws = {0};
        for (GisVectorLayer layer : layers) {
            String groupKey = groupKeys.get(layer.getGroupId());
            JsonNode group = policies.get(groupKey);
            if (group == null || zoom < group.path("minZoom").asDouble()) continue;
            GeoPackageStore.readDisplay(data, json, layer, definition.path("tilePolicy"), Math.min(18, Math.max(0, (int) zoom - 1)),
                    zoom, resolution,
                    group.path("preserveBoundary").asBoolean(), query, deadline, (id, display) -> {
                        List<VectorRasterStyleBuilder.Draw> resolved = styles.resolve(layer.getId().toString(), groupKey, id, display.properties(), zoom);
                        if (resolved.isEmpty()) return;
                        Geometry shape = display.geometry();
                        coordinates[0] += shape.getNumPoints();
                        if (coordinates[0] > 6_000_000) throw new IllegalStateException("单个绘图块的几何规模超过内存限额，请提高起始层级");
                        if (shape.getDimension() == 2 && !shape.isValid()) shape = GeometryFixer.fix(shape);
                        for (var draw : resolved) {
                            Geometry geometry = shape;
                            String role = draw.paint().role();
                            if ("TEXT".equals(role) && shape.getDimension() == 2) {
                                if (display.anchor() == null) continue;
                                geometry = GEOMETRY.createPoint(display.anchor());
                            } else if (("POLYGON".equals(role) && shape.getDimension() != 2)
                                    || ("LINE".equals(role) && shape.getDimension() != 1)
                                    || ("POINT".equals(role) && shape.getDimension() != 0)) continue;
                            if (++draws[0] > 400_000 || batches.size() > 8192)
                                throw new IllegalStateException("单个绘图块的样式规模超过内存限额，请缩小显示密度");
                            var collection = batches.computeIfAbsent(draw.paint(), key -> new ListFeatureCollection(type));
                            collection.add(SimpleFeatureBuilder.build(type, new Object[]{geometry, draw.label()}, layer.getId() + "-" + id + "-" + draws[0]));
                        }
                    });
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = image.createGraphics();
        MapContent map = new MapContent();
        StreamingRenderer renderer = new StreamingRenderer();
        AtomicReference<Exception> renderingError = new AtomicReference<>();
        try {
            var ordered = new ArrayList<>(batches.entrySet());
            ordered.sort(Comparator.comparingDouble(entry -> entry.getKey().order()));
            for (var entry : ordered) map.addLayer(new FeatureLayer(entry.getValue(), styles.style(entry.getKey())));
            renderer.setMapContent(map);
            RenderingHints hints = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            hints.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            hints.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            renderer.setJava2DHints(hints);
            renderer.setRendererHints(Map.of(StreamingRenderer.OPTIMIZE_FTS_RENDERING_KEY, false));
            renderer.addRenderListener(new RenderListener() {
                public void featureRenderer(org.geotools.api.feature.simple.SimpleFeature feature) { }
                public void errorOccurred(Exception error) { renderingError.compareAndSet(null, error); renderer.stopRendering(); }
            });
            renderer.paint(graphics, new Rectangle(width, height), envelope);
            if (renderingError.get() != null) {
                Exception cause = renderingError.get();
                throw new IOException("配图渲染失败: " + cause.getMessage(), cause);
            }
            return image;
        } catch (Exception ex) {
            image.flush();
            throw ex;
        } finally {
            renderer.setMapContent(null);
            map.dispose();
            graphics.dispose();
        }
    }

    private static double symbolPadding(JsonNode node) {
        double size = 0;
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (Set.of("radius", "size", "width", "strokeWidth", "offsetX", "offsetY", "haloRadius").contains(field.getKey())
                        && field.getValue().isNumber()) size = Math.max(size, Math.abs(field.getValue().asDouble()));
                size = Math.max(size, symbolPadding(field.getValue()));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) size = Math.max(size, symbolPadding(child));
        }
        return size;
    }
}

