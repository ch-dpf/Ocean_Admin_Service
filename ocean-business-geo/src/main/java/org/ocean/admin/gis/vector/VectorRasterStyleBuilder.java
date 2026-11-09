package org.ocean.admin.gis.vector;

import org.geotools.api.style.*;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.styling.StyleBuilder;
import org.ocean.admin.gis.util.VectorStyleRules;
import org.ocean.admin.gis.util.VectorTemplateRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.*;

/** 把已保存配图方案转换为离线渲染步骤，按工作区相同的覆盖顺序解析规则。 */
public final class VectorRasterStyleBuilder {
    public record Paint(double order, String role, JsonNode symbol) { }
    public record Draw(Paint paint, String label) { }

    private final ObjectMapper json;
    private final JsonNode cartography;
    private final JsonNode featureOverrides;
    private final Map<String, JsonNode> defaults = new HashMap<>();
    private final VectorTemplateRules templates;
    private final VectorStyleRules rules;
    private final StyleBuilder builder = new StyleBuilder();
    private final Map<Paint, Style> styles = new HashMap<>();

    public VectorRasterStyleBuilder(ObjectMapper json, JsonNode scheme) {
        this.json = json;
        cartography = scheme.path("definition").path("cartography");
        featureOverrides = scheme.path("definition").path("featureOverrides");
        rules = new VectorStyleRules(json);
        templates = new VectorTemplateRules(json, rules);
        for (JsonNode style : cartography.path("defaults").path("styles"))
            defaults.put(style.path("id").asText(), style);
    }

    public boolean visibleLayer(String layerId, String groupKey) {
        JsonNode group = cartography.path("groups").path(groupKey);
        JsonNode layer = cartography.path("layers").path(layerId);
        return group.path("enabled").asBoolean() && layer.path("enabled").asBoolean()
                && layer.path("configured").asBoolean() && defaults.containsKey(group.path("styleKey").asText());
    }

    /** 已在空间查询阶段排除不可见图层；每个要素仅解析属性条件和样式覆盖。 */
    public List<Draw> resolve(String layerId, String groupKey, long featureId, JsonNode properties, double zoom) {
        JsonNode group = cartography.path("groups").path(groupKey);
        JsonNode layer = cartography.path("layers").path(layerId);
        JsonNode base = defaults.get(group.path("styleKey").asText());
        if (base == null || !visibleLayer(layerId, groupKey)) return List.of();
        JsonNode groupOverride = group.path("override");
        JsonNode feature = featureOverrides.path(Long.toString(featureId));
        JsonNode propertyRule = json.missingNode();
        if (layer.path("automatic").asBoolean()) {
            for (JsonNode rule : base.path("overrides")) {
                if (templates.matches(rule.path("filter"), properties)) { propertyRule = rule; break; }
            }
        }
        JsonNode custom = json.missingNode();
        if (layer.hasNonNull("definition")) {
            for (JsonNode rule : layer.path("definition").path("rules")) {
                if (rule.path("geometryType").asText().equals(base.path("geometryType").asText())
                        && inZoom(rule, zoom, 0, Double.POSITIVE_INFINITY)
                        && rules.matchesFilter(rule.get("filter"), properties)) { custom = rule; break; }
            }
            if (custom.isMissingNode()) return List.of();
        }
        if (!firstBoolean("visible", base.path("enabled").asBoolean(), feature, custom, groupOverride))
            return List.of();
        boolean segmented = groupOverride.hasNonNull("mode");
        JsonNode previewSymbol = groupOverride.path("symbol");
        if (segmented) {
            if (!inZoom(groupOverride, zoom, 0, 24)) return List.of();
            if ("SEGMENTED".equals(groupOverride.path("mode").asText())) {
                previewSymbol = json.missingNode();
                for (JsonNode segment : groupOverride.path("segments"))
                    if (inZoom(segment, zoom, 0, 24)) { previewSymbol = segment.path("symbol"); break; }
                if (previewSymbol.isMissingNode()) return List.of();
            }
        } else {
            double min = firstNumber("minZoom", 0, custom, propertyRule, base);
            double max = firstNumber("maxZoom", 24, custom, propertyRule, base);
            if (zoom < min || zoom >= max) return List.of();
        }
        List<Draw> draws = new ArrayList<>();
        JsonNode labels = segmented ? groupOverride.path("labels") : json.missingNode();
        double order = base.path("renderOrder").asDouble();
        if (base.has("renderRules") && custom.isMissingNode()) {
            ObjectNode patch = merge(groupOverride.path("symbol"), previewSymbol, feature.path("symbol"));
            for (JsonNode rule : base.path("renderRules")) {
                if (!inZoom(rule, zoom, 0, 24) || !templates.matches(rule.path("filter"), properties)) continue;
                for (JsonNode step : rule.path("symbolizers")) {
                    String type = step.path("type").asText();
                    ObjectNode symbol = merge(step, patch);
                    if ("TEXT".equals(type)) {
                        if (labels.has("enabled") && !labels.path("enabled").asBoolean()) continue;
                        String text = label(properties.path(step.path("field").asText()));
                        if (text.isBlank()) continue;
                        symbol = merge(step);
                        applyLabels(symbol, labels);
                        draws.add(new Draw(new Paint(10000 + step.path("priority").asDouble(), "TEXT", symbol), text));
                    } else {
                        draws.add(new Draw(new Paint(order + draws.size() * 0.01, type, symbol), ""));
                    }
                }
            }
            return draws;
        }

        ObjectNode symbol = merge(base.path("symbol"), propertyRule.path("symbol"));
        ObjectNode stops = merge(base.path("zoomStops"), propertyRule.path("zoomStops"));
        for (var entry : stops.properties())
            symbol.put(entry.getKey(), interpolate(entry.getValue(), zoom, symbol.path(entry.getKey()).asDouble()));
        symbol = merge(symbol, groupOverride.path("symbol"), custom.path("symbol"), feature.path("symbol"),
                segmented ? previewSymbol : json.missingNode());
        String geometry = base.path("geometryType").asText();
        if ("POINT".equals(geometry)) symbol.put("strokeOpacity", symbol.path("opacity").asDouble(1));
        if ("LINE".equals(geometry) && base.has("casing")) {
            JsonNode casing = base.path("casing");
            ObjectNode outer = json.createObjectNode().put("color", casing.path("color").asText())
                    .put("width", Math.max(symbol.path("width").asDouble(1),
                            interpolate(casing.path("widthStops"), zoom, 0)));
            draws.add(new Draw(new Paint(order - 0.1, "LINE", outer), ""));
        }
        draws.add(new Draw(new Paint(order, geometry, symbol), ""));
        JsonNode baseLabels = base.path("labels");
        boolean labelled = firstBoolean("enabled", false, labels, baseLabels)
                && zoom >= (segmented ? groupOverride.path("minZoom").asDouble() : baseLabels.path("minZoom").asDouble());
        if (labelled) {
            String text = label(properties.path(baseLabels.path("field").asText()));
            if (text.isBlank()) text = label(properties.path(baseLabels.path("fallbackField").asText()));
            if (!text.isBlank()) {
                ObjectNode textSymbol = json.createObjectNode()
                        .put("fontSize", interpolate(baseLabels.path("fontSizeStops"), zoom, 11))
                        .put("fontFamily", "sans-serif").put("color", "#202124")
                        .put("haloColor", "#ffffff").put("haloRadius", 1.5)
                        .put("placement", "LINE".equals(geometry) ? "line" : "point");
                applyLabels(textSymbol, labels);
                draws.add(new Draw(new Paint(order + 1000, "TEXT", textSymbol), text));
            }
        }
        return draws;
    }

    private void applyLabels(ObjectNode symbol, JsonNode labels) {
        for (String key : List.of("fontSize", "fontFamily", "fontWeight", "color"))
            if (labels.has(key)) symbol.set(key, labels.path(key));
        if (labels.has("strokeColor")) symbol.set("haloColor", labels.path("strokeColor"));
        if (labels.has("strokeWidth")) symbol.put("haloRadius", labels.path("strokeWidth").asDouble() / 2);
    }

    private static boolean inZoom(JsonNode rule, double zoom, double min, double max) {
        return zoom >= rule.path("minZoom").asDouble(min) && zoom < rule.path("maxZoom").asDouble(max);
    }

    private static double firstNumber(String key, double fallback, JsonNode... nodes) {
        for (JsonNode node : nodes) if (node.hasNonNull(key)) return node.path(key).asDouble();
        return fallback;
    }

    private static boolean firstBoolean(String key, boolean fallback, JsonNode... nodes) {
        for (JsonNode node : nodes) if (node.hasNonNull(key)) return node.path(key).asBoolean();
        return fallback;
    }

    private ObjectNode merge(JsonNode... nodes) {
        ObjectNode result = json.createObjectNode();
        for (JsonNode node : nodes) if (node.isObject()) result.setAll((ObjectNode) node);
        return result;
    }

    private static String label(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) return "";
        String text = value.asText();
        return text.substring(0, Math.min(200, text.length()));
    }

    private static double interpolate(JsonNode stops, double zoom, double fallback) {
        if (!stops.isArray() || stops.isEmpty()) return fallback;
        if (zoom <= stops.get(0).get(0).asDouble()) return stops.get(0).get(1).asDouble();
        for (int index = 1; index < stops.size(); index++) {
            JsonNode before = stops.get(index - 1), after = stops.get(index);
            double end = after.get(0).asDouble();
            if (zoom <= end) return before.get(1).asDouble() + (zoom - before.get(0).asDouble())
                    / (end - before.get(0).asDouble()) * (after.get(1).asDouble() - before.get(1).asDouble());
        }
        return stops.get(stops.size() - 1).get(1).asDouble();
    }

    /** 同一绘制参数共享 GeoTools 样式，标注内容作为要素属性传入。 */
    public Style style(Paint paint) {
        return styles.computeIfAbsent(paint, key -> {
            JsonNode symbol = key.symbol();
            Symbolizer result = switch (key.role()) {
                case "POLYGON" -> builder.createPolygonSymbolizer(
                        stroke(symbol, "strokeColor", "strokeWidth", "strokeOpacity", "#3388ff", 1),
                        fill(symbol));
                case "LINE" -> builder.createLineSymbolizer(stroke(symbol, "color", "width", "opacity", "#3388ff", 2));
                case "POINT" -> {
                    var mark = builder.createMark(symbol.path("shape").asText("circle"),
                            builder.createFill(color(symbol, "color", "#3388ff"), symbol.path("opacity").asDouble(1)),
                            stroke(symbol, "strokeColor", "strokeWidth", "strokeOpacity",
                                    symbol.path("color").asText("#3388ff"), 0));
                    double diameter = symbol.has("radius") ? symbol.path("radius").asDouble() * 2 : symbol.path("size").asDouble(12);
                    yield builder.createPointSymbolizer(builder.createGraphic(null, mark, null, 1, diameter, 0));
                }
                case "TEXT" -> text(symbol);
                default -> throw new IllegalArgumentException("不支持的栅格配图符号: " + key.role());
            };
            return builder.createStyle(result);
        });
    }

    public void clearStyles() { styles.clear(); }

    private Stroke stroke(JsonNode symbol, String colorKey, String widthKey, String opacityKey, String fallback, double width) {
        double resolvedWidth = symbol.path(widthKey).asDouble(width);
        if (resolvedWidth <= 0) return null;
        Stroke stroke = builder.createStroke(color(symbol, colorKey, fallback), resolvedWidth,
                symbol.path(opacityKey).asDouble(1));
        stroke.setLineCap(builder.literalExpression("round"));
        stroke.setLineJoin(builder.literalExpression("round"));
        if (symbol.path("dashArray").isArray() && !symbol.path("dashArray").isEmpty()) {
            float[] dash = new float[symbol.path("dashArray").size()];
            for (int i = 0; i < dash.length; i++) dash[i] = (float) symbol.path("dashArray").get(i).asDouble();
            stroke.setDashArray(dash);
        }
        return stroke;
    }

    private Fill fill(JsonNode symbol) {
        Color color = color(symbol, "fillColor", "#3388ff");
        double opacity = symbol.path("fillOpacity").asDouble(1);
        String pattern = symbol.path("fillPattern").asText("none");
        if ("none".equals(pattern)) return builder.createFill(color, opacity);
        // 纹理在内存中构造，不允许样式引用外部网络或文件。
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) Math.round(opacity * 255)));
            graphics.setStroke(new BasicStroke(2));
            if ("diagonal".equals(pattern) || "cross".equals(pattern)) {
                graphics.drawLine(-4, 16, 16, -4); graphics.drawLine(0, 20, 20, 0);
            }
            if ("cross".equals(pattern)) {
                graphics.drawLine(-4, 0, 16, 20); graphics.drawLine(0, -4, 20, 16);
            }
            if ("grid".equals(pattern)) { graphics.drawLine(0, 0, 16, 0); graphics.drawLine(0, 0, 0, 16); }
            if ("dots".equals(pattern)) { graphics.fillOval(2, 2, 4, 4); graphics.fillOval(10, 10, 4, 4); }
        } finally { graphics.dispose(); }
        var external = CommonFactoryFinder.getStyleFactory().externalGraphic(new ImageIcon(image), List.of());
        // 属性提取器需要非空标识；图像仍直接使用内存内容，不访问该标识。
        external.setURI("urn:ocean:fill:" + Integer.toHexString(symbol.hashCode()));
        external.setFormat("image/png");
        var graphic = builder.createGraphic(external, null, null, 1, 16, 0);
        Fill fill = builder.createFill(Color.WHITE);
        fill.setGraphicFill(graphic);
        return fill;
    }

    private TextSymbolizer text(JsonNode symbol) {
        String family = symbol.path("fontFamily").asText("sans-serif").replace("\"", "");
        if ("sans-serif".equalsIgnoreCase(family)) family = "SansSerif";
        var font = builder.createFont(family, "italic".equals(symbol.path("fontStyle").asText()),
                "bold".equals(symbol.path("fontWeight").asText()), symbol.path("fontSize").asDouble(11));
        TextSymbolizer text = builder.createTextSymbolizer(color(symbol, "color", "#202124"), font, "label");
        text.setFill(builder.createFill(color(symbol, "color", "#202124"), symbol.path("opacity").asDouble(1)));
        text.setHalo(builder.createHalo(builder.createFill(color(symbol, "haloColor", "#ffffff"),
                symbol.path("haloOpacity").asDouble(1)), symbol.path("haloRadius").asDouble()));
        text.setPriority(builder.literalExpression(symbol.path("priority").asDouble()));
        if ("line".equals(symbol.path("placement").asText())) {
            text.setLabelPlacement(builder.createLinePlacement(0));
            text.getOptions().put("followLine", "true");
            text.getOptions().put("maxAngleDelta", "30");
            if (symbol.path("repeat").asDouble() > 0) text.getOptions().put("repeat", symbol.path("repeat").asText());
        } else {
            double offsetX = symbol.path("offsetX").asDouble(), offsetY = symbol.path("offsetY").asDouble();
            text.setLabelPlacement(builder.createPointPlacement(offsetX == 0 ? 0.5 : 0, 0.5, offsetX, -offsetY, 0));
        }
        text.getOptions().put("conflictResolution", "true");
        text.getOptions().put("partials", "true");
        return text;
    }

    private static Color color(JsonNode symbol, String key, String fallback) {
        return Color.decode(symbol.path(key).asText(fallback));
    }
}

