package org.ocean.admin.gis.ogc;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * WMS GetFeatureInfo 的响应序列化，格式与 GeoServer 保持一致。
 *
 * <p>{@code text/plain} 逐要素输出 {@code Results for FeatureType '<type>':} 表头、
 * 一行连字符与 {@code 属性 = 值} 对；{@code application/json} 输出 GeoJSON FeatureCollection。
 * 渲染与矢量要素查询共用本类，属性顺序沿用调用方传入的顺序。</p>
 */
@Component
public class WmsInfoWriter {

    static final String TEXT = "text/plain";
    static final String JSON = "application/json";

    private static final String HEADER_PREFIX = "Results for FeatureType '";
    private static final String HEADER_SUFFIX = "':";
    private static final String SEPARATOR = "---------------------------------------------";

    private final ObjectMapper objectMapper;

    public WmsInfoWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 输出 GeoServer 同款的纯文本结果；没有要素时返回空字符串。 */
    public String text(List<FeatureInfo> features) {
        StringBuilder body = new StringBuilder();
        for (FeatureInfo feature : features) {
            body.append(HEADER_PREFIX).append(feature.typeName()).append(HEADER_SUFFIX).append('\n');
            body.append(SEPARATOR).append('\n');
            for (Map.Entry<String, Object> property : feature.properties().entrySet()) {
                body.append(property.getKey()).append(" = ")
                        .append(format(property.getValue())).append('\n');
            }
        }
        return body.toString();
    }

    /** 输出 GeoJSON FeatureCollection；没有要素时返回空 features 数组。 */
    public String geojson(List<FeatureInfo> features) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        ArrayNode items = root.putArray("features");
        for (FeatureInfo feature : features) {
            ObjectNode item = items.addObject();
            item.put("type", "Feature");
            ObjectNode geometry = item.putObject("geometry");
            geometry.put("type", "Point");
            ArrayNode coordinates = geometry.putArray("coordinates");
            coordinates.add(feature.longitude());
            coordinates.add(feature.latitude());
            ObjectNode properties = item.putObject("properties");
            feature.properties().forEach(properties::putPOJO);
        }
        return objectMapper.writeValueAsString(root);
    }

    private String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double number) {
            // GeoServer 直接使用 Double.toString，整数值同样带 .0 后缀
            return Double.isNaN(number) || Double.isInfinite(number)
                    ? "null" : Double.toString(number);
        }
        return value.toString();
    }

    /**
     * 一条查询结果。
     *
     * @param typeName    要素类型名，栅格图层取服务编码
     * @param longitude   命中像元中心的经度
     * @param latitude    命中像元中心的纬度
     * @param properties  按输出顺序排列的属性；值可为 null 表示该波段为 Nodata
     */
    public record FeatureInfo(
            String typeName,
            double longitude,
            double latitude,
            Map<String, Object> properties) {
    }
}
