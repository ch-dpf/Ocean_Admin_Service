package org.ocean.admin.gis.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 矢量模板协议 */
@Component
@RequiredArgsConstructor
public class VectorTemplateRules {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Set<String> KINDS = Set.of("POINT", "LINE", "POLYGON");
    public static final Set<String> TILE_FIELDS = Set.of("fclass", "code", "id", "name", "ref",
            "bridge", "tunnel", "ctype", "dtype", "dislevel", "kind", "mj", "roadname", "roadcode");
    private final ObjectMapper json;
    private final VectorStyleRules layerRules;

    public record Compiled(JsonNode definition, Map<String, JsonNode> groups, Map<String, JsonNode> styles) { }
    public record Classification(String groupKey, String ruleKey, boolean warning) { }
    public record GeometryPart(JsonNode geometry, String path, String kind) { }

    public Compiled compile(JsonNode definition) {
        if (definition == null || !definition.isObject() || definition.path("schemaVersion").asInt() != 3
                || !"VECTOR_DATASET_STYLE_TEMPLATE".equals(definition.path("kind").asText())) throw bad("须为数据集模板协议3");
        key(definition.path("templateId").asText());
        if (definition.path("templateVersion").asInt() < 1) throw bad("模板版本须大于0");
        Map<String, JsonNode> groups = index(definition.path("groups")), styles = index(definition.path("cartography").path("styles"));
        for (JsonNode group : groups.values()) {
            String parent = group.path("parentId").asText(null);
            Set<String> visited = new HashSet<>();
            visited.add(group.path("id").asText());
            while (parent != null) {
                if (!visited.add(parent) || !groups.containsKey(parent)) throw bad("分组父节点不存在或形成循环");
                parent = groups.get(parent).path("parentId").asText(null);
            }
            if (group.path("name").asText().isBlank()) throw bad("分组名称不能为空");
            if (group.path("assignable").asBoolean()) {
                JsonNode style = styles.get(group.path("defaultStyleId").asText());
                if (style == null || !KINDS.contains(group.path("geometryType").asText())
                        || !style.path("geometryType").equals(group.path("geometryType"))) throw bad("分类的默认样式与几何不一致");
            }
        }
        for (JsonNode style : styles.values()) {
            String kind = style.path("geometryType").asText();
            if (!KINDS.contains(kind)) throw bad("样式几何类别无效");
            bounds(style.path("minZoom").asDouble(Double.NaN), style.path("maxZoom").asDouble(Double.NaN));
            symbol(kind, style.path("symbol"));
            if (style.has("renderRules")) validateRenderRules(style.path("renderRules"), kind);
            if (!style.path("overrides").isArray()) throw bad("overrides须为数组");
            for (JsonNode override : style.path("overrides")) {
                filter(override.path("filter"), 0);
                bounds(override.path("minZoom").asDouble(), override.path("maxZoom").asDouble());
                symbol(kind, merge(style.path("symbol"), override.path("symbol")));
            }
        }
        JsonNode classification = definition.path("classification");
        if (!"FIRST_MATCH".equals(classification.path("strategy").asText())
                || !classification.path("rules").isArray() || classification.path("rules").size() > 512) throw bad("分类须为有序首条命中规则");
        Set<String> ruleIds = new HashSet<>();
        for (JsonNode rule : classification.path("rules")) {
            key(rule.path("id").asText());
            if (!ruleIds.add(rule.path("id").asText())) throw bad("分类规则ID重复");
            JsonNode group = groups.get(rule.path("groupId").asText());
            if (group == null || !group.path("assignable").asBoolean()
                    || !group.path("geometryType").equals(rule.path("geometryType"))) throw bad("分类规则目标不合法");
            if (rule.has("sourceLayers")) {
                if (!rule.path("sourceLayers").isArray() || rule.path("sourceLayers").isEmpty()
                        || rule.path("sourceLayers").size() > 100) throw bad("来源图层清单无效");
                for (var name : rule.path("sourceLayers"))
                    if (!name.isString() || name.asText().isBlank() || name.asText().length() > 255) throw bad("来源图层名称无效");
            }
            if (rule.has("filter")) filter(rule.path("filter"), 0);
            else if (!rule.has("sourceLayers")) throw bad("分类规则缺少匹配条件");
        }
        for (String kind : KINDS) {
            JsonNode group = groups.get(classification.path("fallbackGroupByGeometry").path(kind).asText());
            if (group == null || !group.path("geometryType").asText().equals(kind)
                    || !"UNKNOWN_CLASSIFICATION".equals(group.path("nodeType").asText())) throw bad("未知组兜底不完整");
        }
        JsonNode policy = definition.path("tilePolicy");
        if (policy.path("nativeMaxZoom").asInt() != 18 || policy.path("displayMaxZoom").asInt() != 24
                || policy.path("tileSize").asInt() != 512 || policy.path("extent").asInt() != 8192
                || !policy.path("groups").isArray()) throw bad("瓦片策略无效");
        for (JsonNode item : policy.path("groups")) {
            if (!groups.containsKey(item.path("groupKey").asText())) throw bad("瓦片分类不存在");
            bounds(item.path("minZoom").asDouble(), 25);
        }
        if (!policy.path("propertyFields").isArray() || policy.path("propertyFields").size() > TILE_FIELDS.size())
            throw bad("瓦片属性清单无效");
        for (var field : policy.path("propertyFields"))
            if (!TILE_FIELDS.contains(field.asText())) throw bad("不支持的瓦片属性");
        return new Compiled(definition.deepCopy(), Map.copyOf(groups), Map.copyOf(styles));
    }

    private Map<String, JsonNode> index(JsonNode items) {
        if (!items.isArray() || items.isEmpty() || items.size() > 512) throw bad("目录或样式须为1至512项的数组");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            key(id);
            if (result.put(id, item.deepCopy()) != null) throw bad("目录或样式ID重复");
        }
        return result;
    }

    public Classification classify(Compiled template, JsonNode properties, String kind, String placement, String sourceLayer) {
        JsonNode classification = template.definition().path("classification");
        JsonNode selected = null;
        if (!"UNKNOWN".equals(placement)) {
            for (JsonNode rule : classification.path("rules")) {
                if (!placement.equals(rule.path("placement").asText("AUTO"))) continue;
                if (rule.has("sourceLayers")) {
                    boolean found = false;
                    for (var name : rule.path("sourceLayers")) if (name.asText().equals(sourceLayer)) found = true;
                    if (!found) continue;
                }
                if (kind.equals(rule.path("geometryType").asText()) && matches(rule.path("filter"), properties)) {
                    selected = rule;
                    break;
                }
            }
        }
        if (selected == null) {
            return new Classification(classification.path("fallbackGroupByGeometry").path(kind).asText(), null, false);
        }
        boolean warning = false;
        JsonNode featureClass = normalized("fclass", property(properties, "fclass"));
        JsonNode code = normalized("code", property(properties, "code"));
        if (featureClass != null && code != null) {
            boolean knownCode = false;
            boolean matchingPair = false;
            for (JsonNode rule : classification.path("rules")) {
                if (!kind.equals(rule.path("geometryType").asText())) {
                    continue;
                }
                for (JsonNode source : rule.path("sourceClasses")) {
                    if (equal(code, normalized("code", source.get("code")))) {
                        knownCode = true;
                        if (equal(featureClass, normalized("fclass", source.get("fclass")))) {
                            matchingPair = true;
                        }
                    }
                }
            }
            warning = knownCode && !matchingPair;
        }
        return new Classification(selected.path("groupId").asText(), selected.path("id").asText(), warning);
    }

    public boolean matches(JsonNode filter, JsonNode properties) {
        if (filter == null || filter.isNull() || filter.isMissingNode()) {
            return true;
        }
        String operator = filter.path("op").asText();
        if ("and".equals(operator)) {
            for (JsonNode condition : filter.path("conditions")) {
                if (!matches(condition, properties)) {
                    return false;
                }
            }
            return true;
        }
        if ("or".equals(operator)) {
            for (JsonNode condition : filter.path("conditions")) {
                if (matches(condition, properties)) {
                    return true;
                }
            }
            return false;
        }
        if ("not".equals(operator)) {
            return !matches(filter.path("condition"), properties);
        }
        String field = filter.path("field").asText();
        JsonNode raw = property(properties, field);
        if ("isNull".equals(operator)) return raw == null || raw.isNull();
        if ("missingOrEmpty".equals(operator)) {
            return empty(raw);
        }
        JsonNode actual = normalized(field, raw);
        JsonNode expected = normalized(field, filter.get("value"));
        if (actual == null) {
            return false;
        }
        return switch (operator) {
            case "eq" -> equal(actual, expected);
            case "ne" -> !equal(actual, expected);
            case "in" -> {
                boolean found = false;
                for (JsonNode value : filter.path("value")) {
                    if (equal(actual, normalized(field, value))) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            case "regex" -> Pattern.matches(filter.path("value").asText(), actual.asText());
            case "gt", "gte", "lt", "lte" -> {
                if (!actual.isNumber() || expected == null || !expected.isNumber()) yield false;
                int compared = actual.decimalValue().compareTo(expected.decimalValue());
                yield switch (operator) {
                    case "gt" -> compared > 0;
                    case "gte" -> compared >= 0;
                    case "lt" -> compared < 0;
                    default -> compared <= 0;
                };
            }
            default -> false;
        };
    }

    private JsonNode normalized(String field, JsonNode value) {
        if (empty(value)) {
            return null;
        }
        if ("fclass".equals(field)) {
            if (value.isString()) {
                return json.valueToTree(value.asText().trim().toLowerCase(Locale.ROOT));
            }
            return null;
        }
        if ("id".equals(field)) {
            if (value.isString()) {
                return json.valueToTree(value.asText().trim());
            }
            if (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0) {
                return json.valueToTree(value.decimalValue().toBigInteger().toString());
            }
            return null;
        }
        if ("code".equals(field)) {
            if (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0) {
                return value;
            }
            if (value.isString() && value.asText().trim().matches("[+-]?[0-9]+")) {
                return json.valueToTree(new BigDecimal(value.asText().trim()));
            }
            return null;
        }
        return value;
    }

    private boolean equal(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        return actual.equals(expected);
    }

    private JsonNode property(JsonNode properties, String field) {
        if (properties != null && properties.isObject()) {
            return properties.get(field);
        }
        return null;
    }

    private boolean empty(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode()
                || value.isString() && value.asText().trim().isEmpty();
    }

    private void filter(JsonNode f, int depth) {
        if (depth > 16 || f == null || !f.isObject()) throw bad("属性条件无效或嵌套过深");
        switch (f.path("op").asText()) {
            case "and", "or" -> {
                if (!f.path("conditions").isArray() || f.path("conditions").isEmpty() || f.path("conditions").size() > 100) throw bad("组合条件无效");
                for (JsonNode child : f.path("conditions")) filter(child, depth + 1);
            }
            case "not" -> filter(f.path("condition"), depth + 1);
            case "eq", "ne", "in", "regex", "missingOrEmpty", "isNull", "gt", "gte", "lt", "lte" -> {
                if (f.path("field").asText().isBlank() || f.path("field").asText().length() > 255) throw bad("属性字段无效");
                if ("in".equals(f.path("op").asText()) && (!f.path("value").isArray() || f.path("value").size() > 100)) throw bad("in条件无效");
                if ("regex".equals(f.path("op").asText())) {
                    String expression = f.path("value").asText();
                    if (expression.length() > 100 || !Set.of("^[0-9]{2}0000$", "^[0-9]{4}00$", "^[0-9]{6}$").contains(expression)) throw bad("仅允许行政编码白名单正则");
                }
            }
            default -> throw bad("不支持的模板属性运算符");
        }
    }

    /** 复合配图使用全部命中规则，保持几何、文字和多重描边的绘制顺序。 */
    private void validateRenderRules(JsonNode rules, String kind) {
        if (!rules.isArray() || rules.isEmpty() || rules.size() > 128) throw bad("复合配图规则数量无效");
        Set<String> ids = new HashSet<>();
        for (var rule : rules) {
            key(rule.path("id").asText());
            if (!ids.add(rule.path("id").asText())) throw bad("复合配图规则ID重复");
            double min = rule.path("minScaleDenominator").asDouble(0);
            double max = rule.path("maxScaleDenominator").asDouble(Double.MAX_VALUE);
            if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0 || min >= max) throw bad("比例尺范围无效");
            if (rule.has("filter")) { filter(rule.path("filter"), 0); validateRenderFields(rule.path("filter")); }
            var symbols = rule.path("symbolizers");
            if (!symbols.isArray() || symbols.isEmpty() || symbols.size() > 8) throw bad("复合符号数量无效");
            for (var item : symbols) {
                String type = item.path("type").asText();
                if (!type.equals(kind) && !"TEXT".equals(type)) throw bad("复合符号与图层几何不一致");
                for (String field : item.propertyNames()) {
                    var value = item.path(field);
                    if (field.toLowerCase(Locale.ROOT).endsWith("color") && !value.asText().matches("#[0-9a-fA-F]{6}"))
                        throw bad("复合符号颜色无效");
                    if (field.toLowerCase(Locale.ROOT).endsWith("opacity")) number(value, 0, 1);
                }
                if ("TEXT".equals(type)) {
                    if (!TILE_FIELDS.contains(item.path("field").asText())) throw bad("不支持的标注字段");
                    number(item.path("fontSize"), 1, 64); number(item.path("haloRadius"), 0, 12);
                    number(item.path("offsetX"), -64, 64); number(item.path("offsetY"), -64, 64);
                    if (!Set.of("normal", "bold").contains(item.path("fontWeight").asText())
                            || !Set.of("normal", "italic").contains(item.path("fontStyle").asText())
                            || !Set.of("point", "line").contains(item.path("placement").asText())
                            || item.path("fontFamily").asText().length() > 100) throw bad("标注格式无效");
                    number(item.path("repeat"), 0, 4096); number(item.path("priority"), 0, 10000);
                } else if ("POINT".equals(type)) {
                    if (!Set.of("circle", "star").contains(item.path("shape").asText())) throw bad("点符号形状无效");
                    number(item.path("size"), 1, 24); number(item.path("strokeWidth"), 0, 12);
                } else {
                    var value = (ObjectNode) item.deepCopy(); value.remove("type");
                    symbol(type, value);
                    number(item.path("LINE".equals(type) ? "width" : "strokeWidth"), 0, 12);
                }
            }
        }
    }

    private void validateRenderFields(JsonNode filter) {
        if (filter.has("conditions")) for (var child : filter.path("conditions")) validateRenderFields(child);
        else if (filter.has("condition")) validateRenderFields(filter.path("condition"));
        else if (!TILE_FIELDS.contains(filter.path("field").asText())) throw bad("复合配图条件未包含在瓦片属性中");
    }

    private void number(JsonNode value, double min, double max) {
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min || value.asDouble() > max)
            throw bad("复合符号数值超出允许范围");
    }

    public List<GeometryPart> parts(JsonNode geometry) {
        List<GeometryPart> result = new ArrayList<>();
        parts(geometry, "/geometry", 0, result);
        return result;
    }
    private void parts(JsonNode g, String path, int depth, List<GeometryPart> out) {
        if (g == null || depth > 16) throw bad("几何集合嵌套过深");
        if ("GeometryCollection".equals(g.path("type").asText())) {
            int i = 0;
            for (JsonNode child : g.path("geometries")) parts(child, path + "/geometries/" + i++, depth + 1, out);
        } else out.add(new GeometryPart(g, path, kind(g)));
    }
    public String kind(JsonNode geometry) {
        return switch (geometry.path("type").asText()) {
            case "Point", "MultiPoint" -> "POINT";
            case "LineString", "MultiLineString" -> "LINE";
            case "Polygon", "MultiPolygon" -> "POLYGON";
            default -> throw bad("图层仅允许同类别几何；集合须在导入时拆分");
        };
    }

    public JsonNode groupStyle(Compiled template, String groupKey) {
        JsonNode group = template.groups().get(groupKey);
        if (group == null || !group.path("assignable").asBoolean()) throw bad("目标不是可挂载分类");
        return template.styles().get(group.path("defaultStyleId").asText());
    }

    public ArrayNode displayRules(JsonNode style, boolean automatic) {
        ArrayNode result = json.createArrayNode();
        if (automatic) for (JsonNode override : style.path("overrides")) {
            ObjectNode r = displayRule(style, override.path("id").asText());
            r.set("filter", override.path("filter").deepCopy());
            r.put("minZoom", override.path("minZoom").asDouble());
            r.put("maxZoom", override.path("maxZoom").asDouble());
            r.set("symbol", merge(style.path("symbol"), override.path("symbol")));
            result.add(r);
        }
        result.add(displayRule(style, style.path("id").asText()));
        return result;
    }
    private ObjectNode displayRule(JsonNode style, String id) {
        ObjectNode result = json.createObjectNode();
        result.put("id", id);
        result.put("minZoom", style.path("minZoom").asDouble());
        result.put("maxZoom", style.path("maxZoom").asDouble());
        result.put("visible", true);
        result.set("symbol", style.path("symbol").deepCopy());
        return result;
    }
    public ObjectNode merge(JsonNode base, JsonNode patch) {
        if (base == null || !base.isObject() || patch == null || !patch.isObject()) throw bad("符号须为对象");
        ObjectNode result = (ObjectNode) base.deepCopy();
        for (String field : patch.propertyNames()) result.set(field, patch.get(field).deepCopy());
        return result;
    }

    public JsonNode symbol(String kind, JsonNode symbol) {
        if (symbol == null || !symbol.isObject()) throw bad("符号须为对象");
        ObjectNode copy = (ObjectNode) symbol.deepCopy();
        JsonNode dash = copy.remove("dashArray");
        ObjectNode definition = json.createObjectNode().put("schemaVersion", 1);
        ObjectNode rule = json.createObjectNode().put("id", "symbol").put("geometryType", kind);
        rule.set("symbol", copy);
        definition.set("rules", json.createArrayNode().add(rule));
        JsonNode validated = layerRules.validate(definition).path("rules").get(0).path("symbol");
        if (dash != null) {
            if ("POINT".equals(kind) || !dash.isArray() || dash.size() > 16) throw bad("虚线数组无效");
            for (JsonNode n : dash) if (!n.isNumber() || !Double.isFinite(n.asDouble()) || n.asDouble() <= 0 || n.asDouble() > 128) throw bad("虚线长度无效");
            ((ObjectNode) validated).set("dashArray", dash.deepCopy());
        }
        return validated;
    }
    public void bounds(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0 || min > 18 || max > 25 || min >= max) throw bad("显示范围须满足0≤开始级别≤18且开始级别<结束级别≤25");
    }
    private void key(String value) { if (!KEY.matcher(value).matches()) throw bad("模板业务键无效"); }
    private IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
