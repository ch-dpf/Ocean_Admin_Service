package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.util.VectorTemplateRules;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把发布时冻结的矢量样式方案解析成逐要素绘制计划。
 *
 * <p>规则优先级与前端配图一致：分类默认样式 → 自动分类属性覆盖 → 分类覆盖 →
 * 图层自定义规则 → 要素覆盖。属性过滤与覆盖必须在拿到要素属性后才能判定，
 * 因此这里先按层级整理每层的固定配置，绘制时再逐要素合成符号。</p>
 */
@Service
public class VectorStyleResolver {

    private static final Set<String> SYMBOL_FIELDS = Set.of(
            "color", "radius", "opacity", "strokeColor", "strokeWidth",
            "width", "dashArray", "fillColor", "fillOpacity", "strokeOpacity");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final VectorTemplateRules templateRules;

    public VectorStyleResolver(VectorTemplateRules templateRules) {
        this.templateRules = templateRules;
    }

    /** 按层级整理所有启用图层的固定样式；被禁用或层级不匹配的图层不在结果中。 */
    public StyleContext resolve(PublishedVectorCatalog.PublishedVectorLayer layer, double zoom) {
        JsonNode groups = layer.cartography().path("groups");
        JsonNode layerConfigs = layer.cartography().path("layers");
        JsonNode featureOverrides = layer.scheme().path("definition").path("featureOverrides");
        List<LayerPlan> plans = new ArrayList<>();
        for (JsonNode frozen : layer.layers()) {
            long layerId = frozen.path("id").asLong();
            String layerIdText = Long.toString(layerId);
            JsonNode config = layerConfigs.path(layerIdText);
            if (!config.isObject() || !config.path("enabled").asBoolean()
                    || !config.path("configured").asBoolean()) {
                continue;
            }
            long groupId = frozen.path("groupId").asLong();
            String groupKey = layer.groupKey(groupId);
            JsonNode group = groups.path(groupKey);
            if (!group.isObject() || !group.path("enabled").asBoolean()
                    || !group.path("id").asText().equals(Long.toString(groupId))) {
                continue;
            }
            JsonNode base = layer.baseStyle(layer.defaultStyleKey(groupId));
            if (base == null || !base.path("enabled").asBoolean()
                    || zoom < base.path("minZoom").asDouble(0)
                    || zoom >= base.path("maxZoom").asDouble(25)) {
                continue;
            }
            JsonNode symbol = mergeSymbol(base.path("symbol"),
                    group.path("override").path("symbol"));
            List<JsonNode> propertyRules = new ArrayList<>();
            if ("AUTO".equals(frozen.path("assignmentMode").asText())) {
                for (JsonNode override : base.path("overrides")) {
                    if (zoom >= override.path("minZoom").asDouble(0)
                            && zoom < override.path("maxZoom").asDouble(25)) {
                        propertyRules.add(override);
                    }
                }
            }
            plans.add(new LayerPlan(layerId, layerIdText,
                    frozen.path("layerName").asText(layerIdText),
                    frozen.path("geometryType").asText(),
                    base.path("renderOrder").asInt(0), symbol,
                    mergeStops(base.path("zoomStops")), propertyRules,
                    config.hasNonNull("definition") ? config.path("definition").path("rules") : null,
                    base.path("labels").isObject() ? base.path("labels") : null,
                    base.path("casing").isObject() ? base.path("casing") : null,
                    featureOverrides, zoom,
                    group.path("override").path("visible").asBoolean(true)));
        }
        plans.sort(Comparator.comparingInt(LayerPlan::renderOrder)
                .thenComparingLong(LayerPlan::layerId));
        Map<Long, LayerPlan> byId = new LinkedHashMap<>();
        for (LayerPlan plan : plans) {
            byId.put(plan.layerId(), plan);
        }
        return new StyleContext(List.copyOf(plans), byId, templateRules);
    }

    /** 合并符号对象；覆盖可缺省，缺省时保留基准符号的独立副本。 */
    private JsonNode mergeSymbol(JsonNode base, JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            return base == null || !base.isObject() ? NODES.objectNode() : base.deepCopy();
        }
        return templateRules.merge(base, patch);
    }

    /** 保留当前层级需要插值的符号字段阶梯，绘制时按层级覆盖到最终符号上。 */
    private JsonNode mergeStops(JsonNode zoomStops) {
        ObjectNode stops = NODES.objectNode();
        if (!zoomStops.isObject()) {
            return stops;
        }
        for (String name : zoomStops.propertyNames()) {
            if (SYMBOL_FIELDS.contains(name) && zoomStops.get(name).isArray()) {
                stops.set(name, zoomStops.get(name));
            }
        }
        return stops;
    }

    /** 一次 GetMap / GetTile 请求中所有启用图层的绘制准备结果。 */
    public record StyleContext(
            List<LayerPlan> plans,
            Map<Long, LayerPlan> byLayer,
            VectorTemplateRules templateRules) {

        /** 逐要素合成符号；返回 null 表示该要素在该层级不显示。 */
        public JsonNode symbol(long layerId, JsonNode properties, String featureId) {
            LayerPlan plan = byLayer.get(layerId);
            if (plan == null || !plan.groupVisible()) {
                return null;
            }
            JsonNode symbol = plan.baseSymbol().deepCopy();
            JsonNode propertyMatch = null;
            for (JsonNode override : plan.propertyRules()) {
                if (templateRules.matches(override.path("filter"), properties)) {
                    propertyMatch = override;
                    break;
                }
            }
            if (propertyMatch != null) {
                symbol = merge(symbol, propertyMatch.path("symbol"));
            }
            JsonNode custom = null;
            if (plan.customRules() != null) {
                for (JsonNode rule : plan.customRules()) {
                    if (!plan.geometryType().equals(rule.path("geometryType").asText())
                            || plan.zoom() < rule.path("minZoom").asDouble(0)
                            || plan.zoom() >= rule.path("maxZoom").asDouble(22)) {
                        continue;
                    }
                    JsonNode filter = rule.path("filter");
                    if (!customMatches(filter, properties)) {
                        continue;
                    }
                    custom = rule;
                    break;
                }
                if (custom == null || !custom.path("visible").asBoolean(true)) {
                    return null;
                }
                symbol = merge(symbol, custom.path("symbol"));
            }
            JsonNode override = plan.override(featureId);
            if (override != null) {
                if (!override.path("visible").asBoolean(true)) {
                    return null;
                }
                symbol = merge(symbol, override.path("symbol"));
            }
            JsonNode result = symbol.isObject() ? (ObjectNode) symbol : NODES.objectNode();
            for (String name : plan.zoomStops().propertyNames()) {
                double value = interpolate(plan.zoomStops().get(name), plan.zoom(),
                        result.path(name).asDouble(1));
                ((ObjectNode) result).put(name, value);
            }
            return result;
        }

        /** 用现有样式协议的过滤器执行图层自定义规则的属性条件。 */
        private boolean customMatches(JsonNode filter, JsonNode properties) {
            if (filter == null || filter.isMissingNode() || filter.isNull()) {
                return true;
            }
            return matches(filter, properties);
        }

        private boolean matches(JsonNode filter, JsonNode properties) {
            String operator = filter.path("op").asText();
            if ("and".equals(operator)) {
                for (JsonNode child : filter.path("conditions")) {
                    if (!matches(child, properties)) {
                        return false;
                    }
                }
                return true;
            }
            if ("or".equals(operator)) {
                for (JsonNode child : filter.path("conditions")) {
                    if (matches(child, properties)) {
                        return true;
                    }
                }
                return false;
            }
            JsonNode actual = properties == null || !properties.isObject()
                    ? null : properties.get(filter.path("field").asText());
            JsonNode expected = filter.get("value");
            if ("exists".equals(operator)) {
                return (actual != null) == (expected == null || expected.asBoolean());
            }
            if (actual == null) {
                return false;
            }
            return switch (operator) {
                case "eq" -> scalarEquals(actual, expected);
                case "ne" -> !scalarEquals(actual, expected);
                case "in" -> {
                    boolean found = false;
                    for (JsonNode value : expected) {
                        if (scalarEquals(actual, value)) {
                            found = true;
                            break;
                        }
                    }
                    yield found;
                }
                case "gt", "gte", "lt", "lte" -> {
                    if (!actual.isNumber() || expected == null || !expected.isNumber()) {
                        yield false;
                    }
                    int comparison = actual.decimalValue().compareTo(expected.decimalValue());
                    yield switch (operator) {
                        case "gt" -> comparison > 0;
                        case "gte" -> comparison >= 0;
                        case "lt" -> comparison < 0;
                        default -> comparison <= 0;
                    };
                }
                default -> false;
            };
        }

        private boolean scalarEquals(JsonNode left, JsonNode right) {
            if (left.isNumber() && right.isNumber()) {
                return left.decimalValue().compareTo(right.decimalValue()) == 0;
            }
            return left.equals(right);
        }

        private JsonNode merge(JsonNode base, JsonNode patch) {
            if (patch == null || !patch.isObject()) {
                return base;
            }
            return templateRules.merge(base, patch);
        }

        private double interpolate(JsonNode stops, double zoom, double fallback) {
            if (stops.isEmpty()) {
                return fallback;
            }
            JsonNode first = stops.get(0);
            if (zoom <= first.get(0).asDouble()) {
                return first.get(1).asDouble();
            }
            for (int index = 1; index < stops.size(); index++) {
                JsonNode current = stops.get(index);
                JsonNode previous = stops.get(index - 1);
                double endZoom = current.get(0).asDouble();
                if (zoom <= endZoom) {
                    double startZoom = previous.get(0).asDouble();
                    double start = previous.get(1).asDouble();
                    return start + (current.get(1).asDouble() - start)
                            * (zoom - startZoom) / (endZoom - startZoom);
                }
            }
            return stops.get(stops.size() - 1).get(1).asDouble();
        }
    }

    /** 单个冻结图层的固定绘制参数；一项服务内多个文件组会有多条 LayerPlan。 */
    public record LayerPlan(
            long layerId,
            String layerIdText,
            String layerName,
            String geometryType,
            int renderOrder,
            JsonNode baseSymbol,
            JsonNode zoomStops,
            List<JsonNode> propertyRules,
            JsonNode customRules,
            JsonNode labels,
            JsonNode casing,
            JsonNode featureOverrides,
            double zoom,
            boolean groupVisible) {

        /** 要素覆盖 ID 即要素 ID 字符串；未配置时返回 null。 */
        public JsonNode override(String featureId) {
            if (featureOverrides == null || !featureOverrides.isObject()) {
                return null;
            }
            JsonNode value = featureOverrides.path(featureId);
            return value.isObject() ? value : null;
        }
    }
}
