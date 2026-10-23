package org.ocean.admin.gis.util;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 引擎无关的样式协议 v1；有序规则取首个匹配项，属性条件不执行脚本或 SQL。 */
@Component
@RequiredArgsConstructor
public class VectorStyleRules {
    private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> GEOMETRIES = Set.of("POINT", "LINE", "POLYGON");
    private static final Set<String> COMPARISONS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "exists");
    private final ObjectMapper json;

    /** 返回已补齐符号默认值的独立副本，确保读取与匹配采用同一协议。 */
    public JsonNode validate(JsonNode definition) {
        fields(definition, Set.of("schemaVersion", "rules"), "definition");
        if (!definition.path("schemaVersion").isIntegralNumber()
                || definition.path("schemaVersion").decimalValue().compareTo(java.math.BigDecimal.ONE) != 0) {
            throw invalid("仅支持 schemaVersion=1");
        }
        JsonNode rules = definition.get("rules");
        if (rules == null || !rules.isArray() || rules.size() > 128) throw invalid("rules 须为最多128项的数组");
        if (json.writeValueAsString(definition).getBytes(StandardCharsets.UTF_8).length > 128 * 1024) throw invalid("样式定义不能超过128KiB");
        JsonNode normalized = definition.deepCopy();
        Set<String> ids = new HashSet<>();
        int[] conditions = {0};
        for (JsonNode rule : normalized.path("rules")) {
            fields(rule, Set.of("id", "geometryType", "filter", "minZoom", "maxZoom", "visible", "symbol"), "rule");
            String id = text(rule.get("id"), "rule.id", 64);
            if (!RULE_ID.matcher(id).matches() || !ids.add(id)) throw invalid("规则ID须唯一，仅包含字母、数字、下划线或连字符");
            String geometry = text(rule.get("geometryType"), "geometryType", 16);
            if (!GEOMETRIES.contains(geometry)) throw invalid("geometryType 仅支持 POINT、LINE、POLYGON");
            Double min = zoomBound(rule.get("minZoom"));
            Double max = zoomBound(rule.get("maxZoom"));
            if (min != null && max != null && min >= max) throw invalid("minZoom 须小于 maxZoom");
            JsonNode filter = rule.get("filter");
            if (filter != null && !filter.isNull()) validateFilter(filter, 0, conditions);
            JsonNode visible = rule.get("visible");
            if (visible != null && !visible.isBoolean()) throw invalid("visible 须为布尔值");
            if (visible != null && !visible.asBoolean()) {
                if (rule.has("symbol") && !rule.get("symbol").isNull()) normalizeSymbol(rule.get("symbol"), geometry);
            } else {
                ((ObjectNode) rule).put("visible", true);
                normalizeSymbol(rule.get("symbol"), geometry);
            }
        }
        if (json.writeValueAsString(normalized).getBytes(StandardCharsets.UTF_8).length > 128 * 1024) {
            throw invalid("样式定义不能超过128KiB");
        }
        return normalized;
    }

    public List<GisVectorVO.RuleMatch> match(JsonNode definition, JsonNode geometry, JsonNode properties, double zoom) {
        requireZoom(zoom);
        JsonNode normalized = validate(definition);
        List<GisVectorVO.RuleMatch> result = new ArrayList<>();
        matchGeometry(normalized.path("rules"), geometry, properties, zoom, "/geometry", 0, result);
        return List.copyOf(result);
    }

    public void requireZoom(double zoom) {
        if (!Double.isFinite(zoom) || zoom < 0) throw invalid("zoom 须为非负有限数值，可包含小数");
    }

    private void matchGeometry(JsonNode rules, JsonNode geometry, JsonNode properties, double zoom,
            String path, int depth, List<GisVectorVO.RuleMatch> result) {
        if (depth > 16 || geometry == null || !geometry.isObject()) throw invalid("无效几何");
        String type = geometry.path("type").asText();
        if ("GeometryCollection".equals(type)) {
            JsonNode parts = geometry.get("geometries");
            if (parts == null || !parts.isArray()) throw invalid("GeometryCollection 缺少 geometries");
            for (int i = 0; i < parts.size(); i++) matchGeometry(rules, parts.get(i), properties, zoom,
                    path + "/geometries/" + i, depth + 1, result);
            return;
        }
        String category = switch (type) {
            case "Point", "MultiPoint" -> "POINT";
            case "LineString", "MultiLineString" -> "LINE";
            case "Polygon", "MultiPolygon" -> "POLYGON";
            default -> throw invalid("不支持的几何类型: " + type);
        };
        for (JsonNode rule : rules) {
            if (!category.equals(rule.path("geometryType").asText())) continue;
            Double min = zoomBound(rule.get("minZoom"));
            Double max = zoomBound(rule.get("maxZoom"));
            if ((min != null && zoom < min) || (max != null && zoom >= max)) continue;
            if (!matchesFilter(rule.get("filter"), properties)) continue;
            boolean visible = rule.path("visible").asBoolean();
            result.add(new GisVectorVO.RuleMatch(path, category, rule.path("id").asText(), visible,
                    visible ? rule.get("symbol").deepCopy() : null));
            return;
        }
        result.add(new GisVectorVO.RuleMatch(path, category, null, false, null));
    }

    private void validateFilter(JsonNode filter, int depth, int[] total) {
        if (depth > 8 || ++total[0] > 512) throw invalid("属性条件最多嵌套8层，整份样式最多512个条件节点");
        if (!filter.isObject()) throw invalid("filter 须为条件对象");
        String op = text(filter.get("op"), "filter.op", 16);
        if ("and".equals(op) || "or".equals(op)) {
            fields(filter, Set.of("op", "conditions"), "组合条件");
            JsonNode items = filter.get("conditions");
            if (items == null || !items.isArray() || items.isEmpty() || items.size() > 32) throw invalid("conditions 须为1至32项的数组");
            for (JsonNode item : items) validateFilter(item, depth + 1, total);
            return;
        }
        fields(filter, Set.of("op", "field", "value"), "属性条件");
        if (!COMPARISONS.contains(op)) throw invalid("不支持的属性运算符: " + op);
        text(filter.get("field"), "filter.field", 255);
        JsonNode value = filter.get("value");
        if ("exists".equals(op)) {
            if (value != null && !value.isBoolean()) throw invalid("exists.value 须为布尔值，省略时为 true");
        } else if ("in".equals(op)) {
            if (value == null || !value.isArray() || value.isEmpty() || value.size() > 100) throw invalid("in.value 须为1至100项的数组");
            for (JsonNode item : value) scalar(item);
        } else {
            scalar(value);
            if (!"eq".equals(op) && !"ne".equals(op) && !value.isNumber()) throw invalid("数值比较的 value 须为数值");
        }
    }

    private boolean matchesFilter(JsonNode filter, JsonNode properties) {
        if (filter == null || filter.isNull()) return true;
        String op = filter.path("op").asText();
        if ("and".equals(op)) {
            for (JsonNode item : filter.path("conditions")) if (!matchesFilter(item, properties)) return false;
            return true;
        }
        if ("or".equals(op)) {
            for (JsonNode item : filter.path("conditions")) if (matchesFilter(item, properties)) return true;
            return false;
        }
        JsonNode actual = properties != null && properties.isObject() ? properties.get(filter.path("field").asText()) : null;
        JsonNode expected = filter.get("value");
        if ("exists".equals(op)) return (actual != null) == (expected == null || expected.asBoolean());
        // 缺失与显式 null 不相同；不等于也不匹配缺失字段，避免意外扩大显示范围。
        if (actual == null) return false;
        if ("eq".equals(op)) return scalarEquals(actual, expected);
        if ("ne".equals(op)) return !scalarEquals(actual, expected);
        if ("in".equals(op)) {
            for (JsonNode value : expected) if (scalarEquals(actual, value)) return true;
            return false;
        }
        if (!actual.isNumber() || !Double.isFinite(actual.asDouble())) return false;
        int comparison = actual.decimalValue().compareTo(expected.decimalValue());
        return switch (op) {
            case "gt" -> comparison > 0;
            case "gte" -> comparison >= 0;
            case "lt" -> comparison < 0;
            case "lte" -> comparison <= 0;
            default -> false;
        };
    }

    private boolean scalarEquals(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue()) == 0;
        return left.equals(right);
    }

    private void normalizeSymbol(JsonNode symbol, String geometry) {
        Set<String> names = switch (geometry) {
            case "POINT" -> Set.of("color", "radius", "opacity", "strokeColor", "strokeWidth");
            case "LINE" -> Set.of("color", "width", "opacity", "dashArray");
            default -> Set.of("fillColor", "fillOpacity", "strokeColor", "strokeWidth", "strokeOpacity");
        };
        fields(symbol, names, "symbol");
        ObjectNode result = (ObjectNode) symbol;
        if ("POLYGON".equals(geometry)) {
            color(symbol.get("fillColor"));
            defaultColor(result, "strokeColor", symbol.get("fillColor").asText());
            number(result, "fillOpacity", 0.25, 0, 1, false);
            number(result, "strokeWidth", 1, 0, 1024, false);
            number(result, "strokeOpacity", 1, 0, 1, false);
        } else {
            color(symbol.get("color"));
            number(result, "opacity", 1, 0, 1, false);
            if ("POINT".equals(geometry)) {
                number(result, "radius", 6, 0, 1024, true);
                defaultColor(result, "strokeColor", symbol.get("color").asText());
                number(result, "strokeWidth", 0, 0, 1024, false);
            } else {
                number(result, "width", 2, 0, 1024, true);
                if (symbol.has("dashArray")) {
                    var dash = symbol.path("dashArray");
                    if (!dash.isArray() || dash.isEmpty() || dash.size() > 16) throw invalid("dashArray须为1至16项数组");
                    for (var n : dash) if (!n.isNumber() || !Double.isFinite(n.asDouble()) || n.asDouble() <= 0 || n.asDouble() > 128) throw invalid("虚线长度无效");
                }
            }
        }
    }

    private void defaultColor(ObjectNode symbol, String field, String fallback) {
        if (!symbol.has(field)) symbol.put(field, fallback);
        color(symbol.get(field));
    }

    private void color(JsonNode value) {
        if (value == null || !value.isString() || !COLOR.matcher(value.asText()).matches()) throw invalid("颜色须为 #RRGGBB");
    }

    private void number(ObjectNode node, String field, double fallback, double min, double max, boolean exclusiveMin) {
        if (!node.has(field)) node.put(field, fallback);
        JsonNode value = node.get(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min
                || value.asDouble() > max || (exclusiveMin && value.asDouble() == min)) throw invalid(field + " 数值超出允许范围");
    }

    private Double zoomBound(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid("层级边界须为数值或 null");
        double number = value.asDouble();
        requireZoom(number);
        return number;
    }

    private void scalar(JsonNode value) {
        if (value == null || !(value.isNull() || value.isBoolean() || value.isString() || value.isNumber())) throw invalid("value 须为 JSON 标量");
        if (value.isString() && value.asText().length() > 4096) throw invalid("条件文本不能超过4096个字符");
        if (value.isNumber() && !Double.isFinite(value.asDouble())) throw invalid("条件数值须为有限数值");
    }

    private String text(JsonNode value, String field, int max) {
        if (value == null || !value.isString() || value.asText().isBlank() || value.asText().length() > max) throw invalid(field + " 须为非空且长度不超过" + max + "的字符串");
        return value.asText();
    }

    private void fields(JsonNode object, Set<String> allowed, String name) {
        if (object == null || !object.isObject()) throw invalid(name + " 须为对象");
        for (String field : object.propertyNames()) if (!allowed.contains(field)) throw invalid(name + " 包含不支持的字段: " + field);
    }

    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
