package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.service.GeoPackageStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 按发布样式把冻结矢量要素渲染成栅格图像，供 WMS GetMap 与 WMTS/TMS 动态瓦片共用。
 *
 * <p>要素从 GPKG 冻结版本按 WGS84 范围读取，逐点投影到请求坐标系，再按画布仿射变换
 * 绘制。所有文件组的图层都在同一次调用中按 renderOrder 排序叠加。</p>
 */
@Service
public class VectorMapRenderer {

    /** 单次窗口读取的原始要素上限，防止极密数据集拖垮一次出图。 */
    private static final int MAX_FEATURES = 20000;
    /** 解码后的窗口要素字节上限；工作进程结果还需容纳 Base64 膨胀，故控制在 8MiB。 */
    private static final int MAX_FEATURE_BYTES = 8 * 1024 * 1024;
    private static final double MERCATOR_R = 20037508.342789244;
    private static final double MAX_LAT = 85.05112877980659;

    private final PublishedVectorCatalog catalog;
    private final GeoPackageStore store;
    private final VectorStyleResolver styleResolver;

    public VectorMapRenderer(PublishedVectorCatalog catalog, GeoPackageStore store,
            VectorStyleResolver styleResolver) {
        this.catalog = catalog;
        this.store = store;
        this.styleResolver = styleResolver;
    }

    /**
     * 把矢量图层画到已有画布上；调用方负责背景与多图层混合顺序。
     *
     * @param window  请求视窗，坐标单位与 window.crs 一致
     * @param layer   已发布矢量服务
     * @param styleId 请求的样式方案 ID，为空时取默认样式
     */
    public void draw(Graphics2D graphics, RenderWindow window,
            PublishedVectorCatalog.PublishedVectorLayer layer, String styleId) {
        PublishedVectorCatalog.PublishedVectorLayer chosen = styleId == null || styleId.isBlank()
                || layer.styleId().equals(styleId) ? layer : reload(layer, styleId);
        if (chosen == null) {
            return;
        }
        VectorStyleResolver.StyleContext styles = styleResolver.resolve(chosen, window.zoom());
        if (styles.plans().isEmpty()) {
            return;
        }
        List<Long> layerIds = new ArrayList<>();
        for (VectorStyleResolver.LayerPlan plan : styles.plans()) {
            layerIds.add(plan.layerId());
        }
        double[] wgs84 = window.wgs84Bounds();
        JsonNode features;
        try {
            features = store.queryWindow(chosen.resource().getStorageKey(), layerIds,
                    wgs84, MAX_FEATURE_BYTES, MAX_FEATURES);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("读取矢量发布版本失败: " + ex.getMessage(), ex);
        }
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (JsonNode feature : features) {
            VectorStyleResolver.LayerPlan plan = styles.byLayer().get(feature.path("layerId").asLong());
            if (plan == null) {
                continue;
            }
            String featureId = feature.path("id").asText("");
            JsonNode properties = feature.path("properties");
            JsonNode symbol = styles.symbol(plan.layerId(), properties, featureId);
            if (symbol == null) {
                continue;
            }
            drawFeature(graphics, window, plan, feature.path("geometry"), symbol, properties);
        }
        drawLabels(graphics, window, styles, features);
    }

    /** 样式方案切换失败时由调用方给出明确异常，不在渲染阶段静默出空白图。 */

    private PublishedVectorCatalog.PublishedVectorLayer reload(
            PublishedVectorCatalog.PublishedVectorLayer layer, String styleId) {
        return catalog.load(layer.serviceCode(), styleId);
    }

    private void drawFeature(Graphics2D graphics, RenderWindow window,
            VectorStyleResolver.LayerPlan plan, JsonNode geometry,
            JsonNode symbol, JsonNode properties) {
        String geometryType = geometry.path("type").asText();
        switch (geometryType) {
            case "Point", "MultiPoint" -> drawPoints(graphics, window, geometry, symbol);
            case "LineString", "MultiLineString" -> drawLines(graphics, window, plan, geometry, symbol);
            case "Polygon", "MultiPolygon" -> drawPolygons(graphics, window, plan, geometry, symbol);
            case "GeometryCollection" -> {
                for (JsonNode part : geometry.path("geometries")) {
                    drawFeature(graphics, window, plan, part, symbol, properties);
                }
            }
            default -> {
            }
        }
    }

    private void drawPoints(Graphics2D graphics, RenderWindow window,
            JsonNode geometry, JsonNode symbol) {
        double radius = symbol.path("radius").asDouble(6);
        Color fill = color(symbol.path("color").asText("#3388ff"),
                symbol.path("opacity").asDouble(1));
        Color stroke = color(symbol.path("strokeColor").asText(symbol.path("color").asText("#3388ff")),
                symbol.path("opacity").asDouble(1));
        float strokeWidth = (float) symbol.path("strokeWidth").asDouble(0);
        for (JsonNode coordinate : pointCoordinates(geometry)) {
            double[] screen = window.toScreen(coordinate.get(0).asDouble(), coordinate.get(1).asDouble());
            double left = screen[0] - radius;
            double top = screen[1] - radius;
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(fill);
            graphics.fillOval((int) Math.round(left), (int) Math.round(top),
                    (int) Math.round(radius * 2), (int) Math.round(radius * 2));
            if (strokeWidth > 0) {
                graphics.setColor(stroke);
                graphics.setStroke(new BasicStroke(strokeWidth));
                graphics.drawOval((int) Math.round(left), (int) Math.round(top),
                        (int) Math.round(radius * 2), (int) Math.round(radius * 2));
            }
        }
    }

    private void drawLines(Graphics2D graphics, RenderWindow window,
            VectorStyleResolver.LayerPlan plan, JsonNode geometry, JsonNode symbol) {
        if (plan.casing() != null) {
            float casingWidth = (float) plan.casing().path("width").asDouble(1);
            graphics.setColor(color(plan.casing().path("color").asText("#000000"), 1));
            graphics.setStroke(new BasicStroke(casingWidth, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            strokePaths(graphics, window, geometry);
        }
        float width = (float) symbol.path("width").asDouble(2);
        graphics.setColor(color(symbol.path("color").asText("#3388ff"),
                symbol.path("opacity").asDouble(1)));
        graphics.setStroke(dash(symbol, width));
        strokePaths(graphics, window, geometry);
    }

    private void drawPolygons(Graphics2D graphics, RenderWindow window,
            VectorStyleResolver.LayerPlan plan, JsonNode geometry, JsonNode symbol) {
        Color fill = color(symbol.path("fillColor").asText("#3388ff"),
                symbol.path("fillOpacity").asDouble(0.25));
        graphics.setColor(fill);
        for (Path2D.Double path : polygonPaths(window, geometry)) {
            graphics.fill(path);
        }
        float strokeWidth = (float) symbol.path("strokeWidth").asDouble(1);
        if (strokeWidth > 0) {
            graphics.setColor(color(symbol.path("strokeColor").asText(
                    symbol.path("fillColor").asText("#3388ff")),
                    symbol.path("strokeOpacity").asDouble(1)));
            graphics.setStroke(dash(symbol, strokeWidth));
            for (Path2D.Double path : polygonPaths(window, geometry)) {
                graphics.draw(path);
            }
        }
    }

    private BasicStroke dash(JsonNode symbol, float width) {
        JsonNode array = symbol.path("dashArray");
        float[] pattern = null;
        if (array.isArray() && !array.isEmpty()) {
            pattern = new float[array.size()];
            for (int index = 0; index < array.size(); index++) {
                pattern[index] = (float) array.get(index).asDouble(1);
            }
        }
        return pattern == null
                ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, pattern, 0f);
    }

    private void strokePaths(Graphics2D graphics, RenderWindow window, JsonNode geometry) {
        if ("MultiLineString".equals(geometry.path("type").asText())) {
            for (JsonNode line : geometry.path("coordinates")) {
                graphics.draw(linePath(window, line));
            }
            return;
        }
        graphics.draw(linePath(window, geometry.path("coordinates")));
    }

    private Path2D.Double linePath(RenderWindow window, JsonNode coordinates) {
        Path2D.Double path = new Path2D.Double();
        boolean started = false;
        for (JsonNode coordinate : coordinates) {
            double[] point = window.toScreen(
                    coordinate.get(0).asDouble(), coordinate.get(1).asDouble());
            if (!started) {
                path.moveTo(point[0], point[1]);
                started = true;
            } else {
                path.lineTo(point[0], point[1]);
            }
        }
        return path;
    }

    private List<Path2D.Double> polygonPaths(RenderWindow window, JsonNode geometry) {
        List<Path2D.Double> paths = new ArrayList<>();
        JsonNode polygons = "MultiPolygon".equals(geometry.path("type").asText())
                ? geometry.path("coordinates") : null;
        if (polygons != null) {
            for (JsonNode polygon : polygons) {
                paths.add(ringPath(window, polygon));
            }
        } else {
            paths.add(ringPath(window, geometry.path("coordinates")));
        }
        return paths;
    }

    private Path2D.Double ringPath(RenderWindow window, JsonNode rings) {
        Path2D.Double path = new Path2D.Double();
        for (JsonNode ring : rings) {
            boolean started = false;
            for (JsonNode coordinate : ring) {
                double[] point = window.toScreen(
                        coordinate.get(0).asDouble(), coordinate.get(1).asDouble());
                if (!started) {
                    path.moveTo(point[0], point[1]);
                    started = true;
                } else {
                    path.lineTo(point[0], point[1]);
                }
            }
            path.closePath();
        }
        return path;
    }

    private List<JsonNode> pointCoordinates(JsonNode geometry) {
        List<JsonNode> result = new ArrayList<>();
        if ("MultiPoint".equals(geometry.path("type").asText())) {
            geometry.path("coordinates").forEach(result::add);
        } else {
            result.add(geometry.path("coordinates"));
        }
        return result;
    }

    private void drawLabels(Graphics2D graphics, RenderWindow window,
            VectorStyleResolver.StyleContext styles, JsonNode features) {
        for (VectorStyleResolver.LayerPlan plan : styles.plans()) {
            JsonNode labels = plan.labels();
            if (labels == null || !labels.path("enabled").asBoolean()
                    || window.zoom() < labels.path("minZoom").asDouble(0)) {
                continue;
            }
            String field = labels.path("field").asText("name");
            String fallback = labels.path("fallbackField").asText("");
            Font font = labelFont(labels, window.zoom());
            graphics.setFont(font);
            for (JsonNode feature : features) {
                if (feature.path("layerId").asLong() != plan.layerId()) {
                    continue;
                }
                JsonNode properties = feature.path("properties");
                String text = text(properties.path(field));
                if (text.isEmpty() && !fallback.isEmpty()) {
                    text = text(properties.path(fallback));
                }
                if (text.isEmpty()) {
                    continue;
                }
                double[] position = labelPosition(window, feature.path("geometry"));
                if (position == null) {
                    continue;
                }
                graphics.setColor(new Color(255, 255, 255, 220));
                graphics.drawString(text, (float) position[0] + 1, (float) position[1] + 1);
                graphics.setColor(new Color(32, 33, 36, 255));
                graphics.drawString(text, (float) position[0], (float) position[1]);
            }
        }
    }

    private Font labelFont(JsonNode labels, double zoom) {
        double size = 11;
        JsonNode stops = labels.path("fontSizeStops");
        if (stops.isArray()) {
            for (int index = 1; index < stops.size(); index++) {
                JsonNode current = stops.get(index);
                JsonNode previous = stops.get(index - 1);
                if (zoom <= current.get(0).asDouble()) {
                    double startZoom = previous.get(0).asDouble();
                    size = previous.get(1).asDouble()
                            + (current.get(1).asDouble() - previous.get(1).asDouble())
                            * (zoom - startZoom) / (current.get(0).asDouble() - startZoom);
                    break;
                }
                size = current.get(1).asDouble();
            }
            if (stops.size() == 1) {
                size = stops.get(0).get(1).asDouble();
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(size));
    }

    private double[] labelPosition(RenderWindow window, JsonNode geometry) {
        String type = geometry.path("type").asText();
        if ("Point".equals(type)) {
            return window.toScreen(geometry.path("coordinates").get(0).asDouble(),
                    geometry.path("coordinates").get(1).asDouble());
        }
        if ("Polygon".equals(type)) {
            JsonNode ring = geometry.path("coordinates").get(0);
            double sumX = 0;
            double sumY = 0;
            int count = 0;
            for (JsonNode coordinate : ring) {
                sumX += coordinate.get(0).asDouble();
                sumY += coordinate.get(1).asDouble();
                count++;
            }
            if (count > 0) {
                return window.toScreen(sumX / count, sumY / count);
            }
        }
        if ("LineString".equals(type)) {
            JsonNode middle = geometry.path("coordinates")
                    .get(geometry.path("coordinates").size() / 2);
            return window.toScreen(middle.get(0).asDouble(), middle.get(1).asDouble());
        }
        return null;
    }

    private String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        String text = value.isString() ? value.asText() : value.toString();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private Color color(String hex, double opacity) {
        try {
            int rgb = Integer.parseInt(hex.trim().substring(1), 16);
            float alpha = (float) Math.max(0, Math.min(1, opacity));
            return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha);
        } catch (RuntimeException ex) {
            return new Color(51, 136, 255, (float) Math.max(0, Math.min(1, opacity)));
        }
    }

    /** 生成一张透明 PNG 瓦片；矢量瓦片不使用 JPEG，避免透明背景被填白。 */
    public static byte[] png(BufferedImage image) {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(image, "png", buffer)) {
                throw new IllegalStateException("当前 JVM 没有可用的 PNG 编码器");
            }
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("编码矢量瓦片失败", ex);
        }
    }

    /**
     * 一次出图的坐标窗口。
     *
     * @param bbox  请求坐标系下的 minX、minY、maxX、maxY
     * @param crs   请求坐标系，仅支持 EPSG:3857、EPSG:4326、CRS:84
     * @param width 图像宽度
     * @param height 图像高度
     * @param zoom  当前标准层级，用于样式规则与 zoomStops
     */
    public record RenderWindow(double[] bbox, String crs, int width, int height, double zoom) {

        /** 把请求坐标窗口换算成 WGS84 查询范围。 */
        public double[] wgs84Bounds() {
            if (mercator()) {
                return new double[]{inverseX(bbox[0]), inverseY(bbox[1]),
                        inverseX(bbox[2]), inverseY(bbox[3])};
            }
            return new double[]{bbox[0], bbox[1], bbox[2], bbox[3]};
        }

        /** WGS84 经纬度到画布像素坐标，y 轴自上而下。 */
        public double[] toScreen(double longitude, double latitude) {
            double worldX = mercator() ? projectX(longitude) : longitude;
            double worldY = mercator() ? projectY(latitude) : latitude;
            double screenX = (worldX - bbox[0]) / (bbox[2] - bbox[0]) * width;
            double screenY = (bbox[3] - worldY) / (bbox[3] - bbox[1]) * height;
            return new double[]{screenX, screenY};
        }

        private boolean mercator() {
            return "EPSG:3857".equalsIgnoreCase(crs);
        }

        private static double projectX(double longitude) {
            return longitude * MERCATOR_R / 180d;
        }

        private static double projectY(double latitude) {
            double clamped = Math.max(-MAX_LAT, Math.min(MAX_LAT, latitude));
            double radians = Math.toRadians(clamped);
            return Math.log(Math.tan(Math.PI / 4d + radians / 2d)) * MERCATOR_R / Math.PI;
        }

        private static double inverseX(double x) {
            return x * 180d / MERCATOR_R;
        }

        private static double inverseY(double y) {
            return Math.toDegrees(Math.atan(Math.sinh(y / MERCATOR_R)));
        }
    }
}
