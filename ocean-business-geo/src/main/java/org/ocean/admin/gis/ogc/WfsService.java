package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.service.GeoPackageStore;
import org.ocean.admin.gis.service.PublicationRecordService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WFS 2.0.0 只读要素服务：能力文档、要素类型描述与 GeoJSON 要素查询。
 *
 * <p>要素来源是矢量发布时冻结的 GPKG 数据版本，不读取工作区当前要素；
 * 事务型操作与服务器端存储过程不在只读发布服务的范围内。</p>
 */
@Service
public class WfsService {

    static final String GEOJSON = "application/geo+json";
    static final String JSON = "application/json";
    static final String TEXT_JSON = "text/json";

    private static final String SERVICE_TYPE = "WFS";
    private static final String VERSION = "2.0.0";
    private static final int MAX_FEATURES = 10000;
    private static final int MAX_FEATURE_BYTES = 8 * 1024 * 1024;
    private static final double[] WORLD_BOUNDS =
            {-180d, -85.05112877980659d, 180d, 85.05112877980659d};
    private static final String CRS84_URN = "urn:ogc:def:crs:OGC:1.3:CRS84";
    private static final String WGS84_URN = "urn:ogc:def:crs:EPSG::4326";
    private static final String MERCATOR_URN = "urn:ogc:def:crs:EPSG::3857";
    private static final double MERCATOR_R = 20037508.342789244;
    private static final double MAX_LAT = 85.05112877980659;

    private final PublicationCatalog catalog;
    private final GeoPackageStore store;
    private final PublicationRecordService recordService;
    private final ObjectMapper objectMapper;

    public WfsService(PublicationCatalog catalog, GeoPackageStore store,
            PublicationRecordService recordService, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.store = store;
        this.recordService = recordService;
        this.objectMapper = objectMapper;
    }

    /** 处理 KVP 请求：GetCapabilities、DescribeFeatureType、GetFeature 或空查询清单。 */
    public WfsReply handleKvp(Map<String, String> requestParameters) {
        Map<String, String> parameters = lowerCaseKeys(requestParameters);
        requireService(parameters);
        String operation = required(parameters.get("request"), "REQUEST");
        return switch (operation.toUpperCase(Locale.ROOT)) {
            case "GETCAPABILITIES" -> document(capabilitiesXml());
            case "DESCRIBEFEATURETYPE" -> document(WfsXmlWriter.describeFeatureType(
                    layersOf(types(parameters, "TYPENAMES"))));
            case "GETFEATURE", "GETPROPERTYVALUE" -> getFeature(parameters);
            case "LISTSTOREDQUERIES", "DESCRIBESTOREDQUERIES" ->
                    document(WfsXmlWriter.emptyStoredQueries(operation));
            default -> throw new WmtsService.OgcException("OperationNotSupported", "REQUEST",
                    "WFS 仅支持只读操作: " + operation);
        };
    }

    /** 生成 GetCapabilities 文档。 */
    public String capabilitiesXml() {
        return WfsXmlWriter.capabilities(
                recordService.buildPublicUrl("/gis/wfs"), catalog.listVector(OgcProtocol.WFS));
    }

    /** 生成 OWS ExceptionReport 文档。 */
    public String exceptionXml(WmtsService.OgcException exception) {
        return WfsXmlWriter.exceptionReport(
                exception.code(), exception.locator(), exception.getMessage());
    }

    /** 按范围、类型和条数输出 GeoJSON FeatureCollection。 */
    private WfsReply getFeature(Map<String, String> parameters) {
        List<PublicationLayer.VectorLayer> layers =
                layersOf(types(parameters, "TYPENAMES"));
        String outputFormat = requireOutputFormat(parameters.get("outputformat"));
        Crs crs = parseCrs(parameters.get("srsname"));
        double[] bbox = parseBbox(parameters.get("bbox"));
        int count = parseCount(parameters.get("count"));
        int startIndex = parseStartIndex(parameters.get("startindex"));
        if (startIndex + count > MAX_FEATURES) {
            throw new WmtsService.OgcException("InvalidParameterValue", "COUNT",
                    "COUNT 与 STARTINDEX 之和不能超过 " + MAX_FEATURES);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        if (crs != Crs.CRS84) {
            ObjectNode crsNode = root.putObject("crs");
            crsNode.put("type", "name");
            crsNode.putObject("properties").put("name", crs.urn());
        }
        ArrayNode features = root.putArray("features");
        for (PublicationLayer.VectorLayer layer : layers) {
            appendFeatures(features, layer, bbox, crs, startIndex, count);
            if (features.size() >= count) {
                break;
            }
        }
        root.put("totalFeatures", features.size());
        root.put("numberReturned", features.size());
        root.put("timeStamp", java.time.Instant.now().toString());
        return new WfsReply(root.toString().getBytes(StandardCharsets.UTF_8),
                outputFormat + ";charset=UTF-8");
    }

    private void appendFeatures(ArrayNode output,
            PublicationLayer.VectorLayer layer, double[] bbox,
            Crs crs, int startIndex, int count) {
        Map<Long, String> layerNames = new LinkedHashMap<>();
        for (JsonNode frozen : layer.layers()) {
            long layerId = frozen.path("id").asLong();
            layerNames.put(layerId,
                    frozen.path("layerName").asText(Long.toString(layerId)));
        }
        int skipped = 0;
        for (JsonNode feature : query(layer, bbox, startIndex + count)) {
            if (skipped < startIndex) {
                skipped++;
                continue;
            }
            long layerId = feature.path("layerId").asLong();
            ObjectNode item = output.addObject();
            item.put("type", "Feature");
            item.put("id", layer.serviceCode() + "." + feature.path("id").asText());
            item.set("geometry", transformGeometry(feature.path("geometry"), crs));
            ObjectNode properties = item.putObject("properties");
            for (String name : feature.path("properties").propertyNames()) {
                properties.set(name, feature.path("properties").get(name).deepCopy());
            }
            properties.put("_typeName", layer.serviceCode());
            properties.put("_layerName", layerNames.getOrDefault(layerId, Long.toString(layerId)));
            if (output.size() >= count) {
                break;
            }
        }
    }

    /** 使用同一个冻结 GPKG 窗口查询，多个文件组的图层一次读取。 */
    private JsonNode query(PublicationLayer.VectorLayer layer,
            double[] wgs84Bounds, int limit) {
        List<Long> layerIds = new ArrayList<>();
        for (JsonNode frozen : layer.layers()) {
            layerIds.add(frozen.path("id").asLong());
        }
        try {
            return store.queryWindow(layer.resource().getStorageKey(), layerIds,
                    wgs84Bounds, MAX_FEATURE_BYTES, limit);
        } catch (IOException | RuntimeException ex) {
            throw new WmtsService.OgcException("NoApplicableCode", null,
                    "读取矢量发布版本失败: " + ex.getMessage());
        }
    }

    private List<PublicationLayer.VectorLayer> layersOf(
            List<String> typeNames) {
        if (typeNames.isEmpty()) {
            return catalog.listVector(OgcProtocol.WFS);
        }
        List<PublicationLayer.VectorLayer> layers = new ArrayList<>();
        for (String typeName : typeNames) {
            PublicationLayer.VectorLayer layer = catalog.loadVector(typeName, null);
            if (layer == null) {
                throw new WmtsService.OgcException("InvalidParameterValue", "TYPENAMES",
                        "要素类型不存在或已停用: " + typeName);
            }
            layers.add(layer);
        }
        return List.copyOf(layers);
    }

    private List<String> types(Map<String, String> parameters, String locator) {
        String value = parameters.get(locator.toLowerCase(Locale.ROOT));
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (String part : value.split(",")) {
            String normalized = part.trim();
            if (!normalized.isEmpty()) {
                // QGIS 等客户端会带命名空间前缀，这里只接受本服务的裸名称
                int separator = normalized.lastIndexOf(':');
                types.add(separator >= 0 ? normalized.substring(separator + 1) : normalized);
            }
        }
        return List.copyOf(types);
    }

    private double[] parseBbox(String value) {
        if (value == null || value.isBlank()) {
            return WORLD_BOUNDS.clone();
        }
        String[] parts = value.split(",");
        if (parts.length != 4 && parts.length != 5) {
            throw new WmtsService.OgcException("InvalidParameterValue", "BBOX",
                    "BBOX 须为4个坐标值或4个坐标值加 CRS: " + value);
        }
        double[] numbers = new double[4];
        for (int index = 0; index < 4; index++) {
            try {
                numbers[index] = Double.parseDouble(parts[index].trim());
            } catch (NumberFormatException ex) {
                throw new WmtsService.OgcException("InvalidParameterValue", "BBOX",
                        "BBOX 包含非法数值: " + parts[index]);
            }
        }
        Crs crs = parts.length == 5 ? parseCrs(parts[4].trim()) : Crs.CRS84;
        double[] bounds = switch (crs) {
            case CRS84 -> numbers;
            case EPSG_4326 -> new double[]{numbers[1], numbers[0], numbers[3], numbers[2]};
            case EPSG_3857 -> new double[]{inverseX(numbers[0]), inverseY(numbers[1]),
                    inverseX(numbers[2]), inverseY(numbers[3])};
        };
        if (bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) {
            throw new WmtsService.OgcException("InvalidParameterValue", "BBOX",
                    "BBOX 范围无效");
        }
        return new double[]{
                Math.max(-180, bounds[0]), Math.max(-MAX_LAT, bounds[1]),
                Math.min(180, bounds[2]), Math.min(MAX_LAT, bounds[3])};
    }

    private Crs parseCrs(String value) {
        String normalized = value == null || value.isBlank() ? CRS84_URN : value.trim();
        if (CRS84_URN.equalsIgnoreCase(normalized) || "CRS:84".equalsIgnoreCase(normalized)) {
            return Crs.CRS84;
        }
        if ("EPSG:4326".equalsIgnoreCase(normalized)
                || WGS84_URN.equalsIgnoreCase(normalized)) {
            return Crs.EPSG_4326;
        }
        if ("EPSG:3857".equalsIgnoreCase(normalized)
                || MERCATOR_URN.equalsIgnoreCase(normalized)) {
            return Crs.EPSG_3857;
        }
        throw new WmtsService.OgcException("InvalidParameterValue", "SRSNAME",
                "SRSNAME 仅支持 CRS84、EPSG:4326 或 EPSG:3857: " + normalized);
    }

    private JsonNode transformGeometry(JsonNode geometry, Crs crs) {
        if (crs == Crs.CRS84) {
            return geometry.deepCopy();
        }
        JsonNode copy = geometry.deepCopy();
        transformCoordinates(copy, crs);
        return copy;
    }

    private void transformCoordinates(JsonNode node, Crs crs) {
        String type = node.path("type").asText();
        if ("GeometryCollection".equals(type)) {
            for (JsonNode child : node.path("geometries")) {
                transformCoordinates(child, crs);
            }
            return;
        }
        JsonNode coordinates = node.get("coordinates");
        if (coordinates == null || !coordinates.isArray()) {
            return;
        }
        switch (type) {
            case "Point" -> transformPoint(coordinates, crs);
            case "LineString", "MultiPoint" -> {
                for (JsonNode point : coordinates) {
                    transformPoint(point, crs);
                }
            }
            case "Polygon", "MultiLineString" -> {
                for (JsonNode line : coordinates) {
                    for (JsonNode point : line) {
                        transformPoint(point, crs);
                    }
                }
            }
            case "MultiPolygon" -> {
                for (JsonNode polygon : coordinates) {
                    for (JsonNode line : polygon) {
                        for (JsonNode point : line) {
                            transformPoint(point, crs);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    private void transformPoint(JsonNode point, Crs crs) {
        if (!point.isArray() || point.size() < 2) {
            return;
        }
        double longitude = point.get(0).asDouble();
        double latitude = point.get(1).asDouble();
        double first = crs == Crs.EPSG_3857 ? projectX(longitude) : latitude;
        double second = crs == Crs.EPSG_3857 ? projectY(latitude) : longitude;
        ((ArrayNode) point).set(0, objectMapper.valueToTree(first));
        ((ArrayNode) point).set(1, objectMapper.valueToTree(second));
    }

    private String requireOutputFormat(String value) {
        String normalized = value == null || value.isBlank()
                ? GEOJSON : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of(GEOJSON, JSON, TEXT_JSON).contains(normalized)) {
            throw new WmtsService.OgcException("InvalidParameterValue", "OUTPUTFORMAT",
                    "OUTPUTFORMAT 仅支持 application/geo+json、application/json 或 text/json");
        }
        return normalized;
    }

    private int parseCount(String value) {
        if (value == null || value.isBlank()) {
            return MAX_FEATURES;
        }
        int count = parseInteger(value, "COUNT");
        if (count < 1) {
            throw new WmtsService.OgcException("InvalidParameterValue", "COUNT",
                    "COUNT 必须为正整数: " + value);
        }
        return Math.min(count, MAX_FEATURES);
    }

    private int parseStartIndex(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int index = parseInteger(value, "STARTINDEX");
        if (index < 0 || index >= MAX_FEATURES) {
            throw new WmtsService.OgcException("InvalidParameterValue", "STARTINDEX",
                    "STARTINDEX 必须在 0 到 " + (MAX_FEATURES - 1) + " 之间: " + value);
        }
        return index;
    }

    private int parseInteger(String value, String locator) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new WmtsService.OgcException("InvalidParameterValue", locator,
                    locator + " 必须为非负整数: " + value);
        }
    }

    private void requireService(Map<String, String> parameters) {
        String service = parameters.get("service");
        if (service != null && !service.isBlank()
                && !SERVICE_TYPE.equalsIgnoreCase(service.trim())) {
            throw new WmtsService.OgcException("InvalidParameterValue", "SERVICE",
                    "SERVICE 仅支持 WFS");
        }
        String version = parameters.get("version");
        if (version != null && !version.isBlank() && !VERSION.equals(version.trim())) {
            throw new WmtsService.OgcException("InvalidParameterValue", "VERSION",
                    "VERSION 仅支持 2.0.0");
        }
    }

    private String required(String value, String locator) {
        if (value == null || value.isBlank()) {
            throw new WmtsService.OgcException("MissingParameterValue", locator,
                    "缺少 " + locator + " 参数");
        }
        return value.trim();
    }

    private Map<String, String> lowerCaseKeys(Map<String, String> requestParameters) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (requestParameters != null) {
            requestParameters.forEach((key, value) -> {
                if (key != null && value != null) {
                    parameters.put(key.toLowerCase(Locale.ROOT), value);
                }
            });
        }
        return parameters;
    }

    private WfsReply document(String xml) {
        return new WfsReply(xml.getBytes(StandardCharsets.UTF_8),
                "application/xml;charset=UTF-8");
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

    /** 输入与输出坐标系；WFS 2.0 规定 EPSG:4326 为纬度在前。 */
    private enum Crs {
        CRS84(CRS84_URN),
        EPSG_4326(WGS84_URN),
        EPSG_3857(MERCATOR_URN);

        private final String urn;

        Crs(String urn) {
            this.urn = urn;
        }

        String urn() {
            return urn;
        }
    }

    /** WFS 响应：能力文档、XSD 与 GeoJSON 统一以字节返回。 */
    public record WfsReply(byte[] body, String mediaType) {
    }
}
