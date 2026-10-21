package org.ocean.admin.gis.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** 逐要素读取 GeoJSON，并为导入和交互编辑执行相同的结构、坐标与资源限制校验。 */
@Component
@RequiredArgsConstructor
public class GeoJsonFeatureReader {
    private static final int MAX_VERTICES = 100_000;
    private static final int MAX_NODES = 400_000;
    private static final int MAX_TEXT = 4 * 1024 * 1024;
    private static final Set<String> WGS84_SOURCE_CRS_NAMES = Set.of(
            "CRS84", "OGC:CRS84", "urn:ogc:def:crs:OGC:1.3:CRS84",
            "urn:ogc:def:crs:OGC::CRS84", "EPSG:4326", "urn:ogc:def:crs:EPSG::4326");
    private final ObjectMapper mapper;

    public record ParsedFeature(long index, String geometry, String properties, String sourceId) { }

    /**
     * 文件导入长期支持旧式 WGS84 命名声明；坐标始终按经度、纬度读取，不猜测轴顺序或转换坐标。
     * 兼容边界仅限文件顶层，在线编辑继续使用标准 GeoJSON；停用这类来源并转换存量文件后可移除。
     */
    public String readSourceCrs(JsonParser parser, int depth) throws IOException {
        if (depth != 0) throw new IllegalArgumentException("仅允许文件顶层声明 crs，不支持子级重复或覆盖声明");
        JsonNode crs = readNode(parser, new Budget(() -> { }), 0);
        if (!crs.isObject() || !"name".equals(crs.path("type").asText())) {
            throw new IllegalArgumentException("crs 必须为受支持的 WGS84 命名对象，不支持 null 或链接型声明");
        }
        JsonNode properties = crs.get("properties");
        JsonNode name = properties != null && properties.isObject() ? properties.get("name") : null;
        if (name == null || !name.isString() || !WGS84_SOURCE_CRS_NAMES.contains(name.asText())) {
            throw new IllegalArgumentException("仅支持 CRS84、EPSG:4326 及约定的 WGS84 URN，请先将其他坐标系转换为 WGS84 经度/纬度");
        }
        return name.asText();
    }

    /** 在进入编辑事务之前完成有界反序列化，避免先整体加载任意大的请求体。 */
    public JsonNode readFeature(InputStream input) throws IOException {
        try (JsonParser parser = mapper.createParser(new FeatureInputStream(input))) {
            parser.nextToken();
            JsonNode feature = readNode(parser, new Budget(() -> { }), 0);
            if (parser.nextToken() != null) throw new IllegalArgumentException("Feature 之后存在额外内容");
            return feature;
        }
    }

    public static final class BodyTooLargeException extends IOException {
        private BodyTooLargeException() { super("单要素编辑请求不能超过4MiB"); }
    }

    private static final class FeatureInputStream extends FilterInputStream {
        private long bytes;
        private FeatureInputStream(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++bytes > MAX_TEXT) throw new BodyTooLargeException();
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, (int) Math.min(length, MAX_TEXT - bytes + 1));
            if (count > 0 && (bytes += count) > MAX_TEXT) throw new BodyTooLargeException();
            return count;
        }
    }

    public static final class FeatureReadException extends IllegalArgumentException {
        private final Long featureIndex;

        public FeatureReadException(Long index, String message) {
            super((index == null ? "GeoJSON" : "features[" + index + "]") + ": " + message);
            this.featureIndex = index;
        }

        public Long featureIndex() { return featureIndex; }
    }

    public long read(Path path, Consumer<ParsedFeature> consumer, Runnable heartbeat) throws IOException {
        long index = 0;
        try (JsonParser parser = mapper.createParser(path.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid(null, "根节点必须为对象");
            ObjectNode root = mapper.createObjectNode();
            Set<String> members = new HashSet<>();
            boolean collection = false;
            Budget rootBudget = new Budget(heartbeat);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw invalid(null, "根对象不完整");
                String name = parser.currentName();
                if (!members.add(name)) throw invalid(null, "存在重复字段: " + name);
                parser.nextToken();
                if ("crs".equals(name)) {
                    try { readSourceCrs(parser, 0); }
                    catch (IllegalArgumentException ex) { throw invalid(null, ex.getMessage()); }
                    continue;
                }
                if ("features".equals(name)) {
                    collection = true;
                    if (parser.currentToken() != JsonToken.START_ARRAY) throw invalid(null, "features 必须为数组");
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        heartbeat.run();
                        try {
                            JsonNode feature = readNode(parser, new Budget(heartbeat), 0);
                            consumer.accept(parseFeature(feature, index));
                        } catch (IllegalArgumentException ex) {
                            if (ex instanceof FeatureReadException) throw ex;
                            throw invalid(index, ex.getMessage());
                        }
                        index++;
                    }
                } else {
                    root.set(name, readNode(parser, rootBudget, 0));
                }
            }
            if (parser.nextToken() != null) throw invalid(null, "根节点之后存在额外内容");
            if (collection) {
                if (!"FeatureCollection".equals(root.path("type").asText())
                        || root.has("geometry") || root.has("coordinates") || root.has("geometries")) {
                    throw invalid(null, "features 只能属于 FeatureCollection");
                }
            } else {
                if ("FeatureCollection".equals(root.path("type").asText())) throw invalid(null, "缺少 features");
                if ("Feature".equals(root.path("type").asText())) {
                    consumer.accept(parseFeature(root, 0));
                } else {
                    ObjectNode feature = mapper.createObjectNode().put("type", "Feature");
                    feature.set("geometry", root);
                    feature.set("properties", mapper.createObjectNode());
                    consumer.accept(parseFeature(feature, 0));
                }
                index = 1;
            }
        }
        return index;
    }

    public ParsedFeature parseFeature(JsonNode feature, long index) {
        try {
            if (feature == null || !feature.isObject() || !"Feature".equals(feature.path("type").asText())) {
                throw new IllegalArgumentException("必须为 GeoJSON Feature");
            }
            if (feature.has("crs") || feature.has("features") || feature.has("coordinates") || feature.has("geometries")) {
                throw new IllegalArgumentException("Feature 包含不支持的结构或 crs 声明");
            }
            JsonNode geometry = feature.get("geometry");
            validateGeometry(geometry, new Coordinates(), 0);
            JsonNode properties = feature.get("properties");
            if (properties == null || (!properties.isNull() && !properties.isObject())) {
                throw new IllegalArgumentException("properties 必须为对象或 null");
            }
            JsonNode sourceId = feature.get("id");
            if (sourceId != null && !sourceId.isString() && !sourceId.isNumber()) {
                throw new IllegalArgumentException("id 必须为字符串或数值");
            }
            String geometryJson = mapper.writeValueAsString(geometry);
            String propertiesJson = mapper.writeValueAsString(properties);
            if ((long) geometryJson.length() + propertiesJson.length() > MAX_TEXT) {
                throw new IllegalArgumentException("单要素几何与属性合计不能超过4Mi个字符");
            }
            if ((long) geometryJson.getBytes(StandardCharsets.UTF_8).length
                    + propertiesJson.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) {
                throw new IllegalArgumentException("单要素几何与属性合计不能超过4MiB");
            }
            return new ParsedFeature(index, geometryJson, propertiesJson,
                    sourceId == null ? null : mapper.writeValueAsString(sourceId));
        } catch (IllegalArgumentException ex) {
            if (ex instanceof FeatureReadException) throw ex;
            throw invalid(index, ex.getMessage());
        }
    }

    private JsonNode readNode(JsonParser parser, Budget budget, int depth) throws IOException {
        if (depth > 32 || ++budget.nodes > MAX_NODES) throw new IllegalArgumentException("单要素结构过深或过大");
        if (budget.nodes % 1024 == 0) budget.heartbeat.run();
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            ObjectNode result = mapper.createObjectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw new IllegalArgumentException("对象不完整");
                String name = parser.currentName();
                budget.text(name);
                if (result.has(name)) throw new IllegalArgumentException("存在重复字段: " + name);
                parser.nextToken();
                result.set(name, readNode(parser, budget, depth + 1));
            }
            return result;
        }
        if (token == JsonToken.START_ARRAY) {
            ArrayNode result = mapper.createArrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) result.add(readNode(parser, budget, depth + 1));
            return result;
        }
        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getString();
            budget.text(value);
            return mapper.getNodeFactory().stringNode(value);
        }
        if (token == JsonToken.VALUE_NUMBER_INT) return mapper.getNodeFactory().numberNode(parser.getBigIntegerValue());
        if (token == JsonToken.VALUE_NUMBER_FLOAT) return mapper.getNodeFactory().numberNode(parser.getDecimalValue());
        if (token == JsonToken.VALUE_TRUE) return mapper.getNodeFactory().booleanNode(true);
        if (token == JsonToken.VALUE_FALSE) return mapper.getNodeFactory().booleanNode(false);
        if (token == JsonToken.VALUE_NULL) return mapper.getNodeFactory().nullNode();
        throw new IllegalArgumentException("JSON 结构不完整");
    }

    private void validateGeometry(JsonNode geometry, Coordinates state, int depth) {
        if (depth > 16 || geometry == null || !geometry.isObject() || geometry.has("crs")) {
            throw new IllegalArgumentException("geometry 必须为非空几何对象，且不能声明 crs");
        }
        String type = geometry.path("type").asText();
        if ("GeometryCollection".equals(type)) {
            if (geometry.has("coordinates")) throw new IllegalArgumentException("GeometryCollection 不能包含 coordinates");
            JsonNode geometries = geometry.get("geometries");
            requireArray(geometries, 1, "geometries");
            for (JsonNode item : geometries) validateGeometry(item, state, depth + 1);
            return;
        }
        if (geometry.has("geometries")) throw new IllegalArgumentException("该几何类型不能包含 geometries");
        JsonNode coordinates = geometry.get("coordinates");
        switch (type) {
            case "Point" -> position(coordinates, state);
            case "MultiPoint" -> {
                requireArray(coordinates, 1, "MultiPoint.coordinates");
                for (JsonNode point : coordinates) position(point, state);
            }
            case "LineString" -> line(coordinates, state, false);
            case "MultiLineString" -> {
                requireArray(coordinates, 1, "MultiLineString.coordinates");
                for (JsonNode item : coordinates) line(item, state, false);
            }
            case "Polygon" -> polygon(coordinates, state);
            case "MultiPolygon" -> {
                requireArray(coordinates, 1, "MultiPolygon.coordinates");
                for (JsonNode item : coordinates) polygon(item, state);
            }
            default -> throw new IllegalArgumentException("不支持的几何类型: " + type);
        }
    }

    private void polygon(JsonNode rings, Coordinates state) {
        requireArray(rings, 1, "Polygon.coordinates");
        for (JsonNode ring : rings) line(ring, state, true);
    }

    private void line(JsonNode points, Coordinates state, boolean ring) {
        requireArray(points, ring ? 4 : 2, ring ? "面环" : "线坐标");
        JsonNode previous = null;
        for (JsonNode point : points) {
            position(point, state);
            if (previous != null && Math.abs(point.get(0).asDouble() - previous.get(0).asDouble()) > 180) {
                throw new IllegalArgumentException("跨日期变更线的线段须先切分为多部件几何");
            }
            previous = point;
        }
        if (ring) {
            JsonNode first = points.get(0);
            JsonNode last = points.get(points.size() - 1);
            for (int i = 0; i < first.size(); i++) {
                if (Double.compare(first.get(i).asDouble(), last.get(i).asDouble()) != 0) {
                    throw new IllegalArgumentException("面环首尾坐标必须闭合");
                }
            }
        }
    }

    private void position(JsonNode point, Coordinates state) {
        requireArray(point, 2, "坐标");
        if (point.size() > 3) throw new IllegalArgumentException("仅支持 XY 或 XYZ 坐标");
        if (state.dimensions != 0 && point.size() != state.dimensions) throw new IllegalArgumentException("单要素不能混合 XY 和 XYZ 坐标");
        state.dimensions = point.size();
        if (++state.vertices > MAX_VERTICES) throw new IllegalArgumentException("单要素不能超过100000个顶点");
        for (int i = 0; i < point.size(); i++) {
            if (!point.get(i).isNumber() || !Double.isFinite(point.get(i).asDouble())) {
                throw new IllegalArgumentException("坐标必须为有限数值");
            }
        }
        if (Math.abs(point.get(0).asDouble()) > 180 || Math.abs(point.get(1).asDouble()) > 90) {
            throw new IllegalArgumentException("经度须在[-180,180]、纬度须在[-90,90]");
        }
    }

    private void requireArray(JsonNode value, int minimum, String name) {
        if (value == null || !value.isArray() || value.size() < minimum) {
            throw new IllegalArgumentException(name + "必须为至少包含" + minimum + "项的数组");
        }
    }

    private FeatureReadException invalid(Long index, String message) { return new FeatureReadException(index, message); }

    private static final class Coordinates { int dimensions; int vertices; }

    private static final class Budget {
        private final Runnable heartbeat;
        private int nodes;
        private long characters;
        private Budget(Runnable heartbeat) { this.heartbeat = heartbeat; }
        private void text(String value) {
            if ((characters += value.length()) > MAX_TEXT) throw new IllegalArgumentException("单要素文本过大");
        }
    }
}
