package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationRecordService;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WMS 协议语义：请求解析、参数校验、动态渲染与像元查询调度。
 *
 * <p>支持 {@code GetCapabilities}、{@code GetMap} 与 {@code GetFeatureInfo}。
 * 响应结构以 1.3.0 为基准；{@code VERSION=1.1.1} 时按旧版约定接受 {@code SRS}、
 * {@code X}/{@code Y}，并按经度在前的顺序解析 BBOX（见 {@link #parseBbox}）。</p>
 */
@Slf4j
@Service
public class WmsService {

    private static final String SERVICE_TYPE = "WMS";
    private static final String VERSION = "1.3.0";
    private static final String LEGACY_VERSION = "1.1.1";
    private static final String DEFAULT_STYLE = "default";
    private static final String WGS84 = "EPSG:4326";
    private static final String INFO_TEXT = WmsInfoWriter.TEXT;
    private static final String INFO_JSON = WmsInfoWriter.JSON;
    private static final int MAX_BUFFER = 20;

    private final PublishedImageryCatalog catalog;
    private final PublicationRecordService recordService;
    private final WmsSourceResolver sourceResolver;
    private final WmsRasterRenderer rasterRenderer;
    private final WmsPixelSampler pixelSampler;
    private final WmsInfoWriter infoWriter;

    public WmsService(PublishedImageryCatalog catalog,
            PublicationRecordService recordService,
            WmsSourceResolver sourceResolver,
            WmsRasterRenderer rasterRenderer,
            WmsPixelSampler pixelSampler,
            WmsInfoWriter infoWriter) {
        this.catalog = catalog;
        this.recordService = recordService;
        this.sourceResolver = sourceResolver;
        this.rasterRenderer = rasterRenderer;
        this.pixelSampler = pixelSampler;
        this.infoWriter = infoWriter;
    }

    /** 处理 KVP 请求：GetCapabilities、GetMap 或 GetFeatureInfo。 */
    public WmsReply handleKvp(Map<String, String> requestParameters) {
        Map<String, String> parameters = lowerCaseKeys(requestParameters);
        requireService(parameters);
        String operation = required(parameters.get("request"), "REQUEST");
        return switch (operation.toUpperCase(Locale.ROOT)) {
            case "GETCAPABILITIES" -> document(capabilitiesXml());
            case "GETMAP" -> renderMap(parseMapRequest(parameters));
            case "GETFEATUREINFO" -> queryInfo(parseInfoRequest(parameters));
            default -> throw new OgcException("OperationNotSupported", "REQUEST",
                    "不支持的操作: " + operation);
        };
    }

    /** 生成 GetCapabilities 文档；服务地址与其它 OGC 入口同源。 */
    public String capabilitiesXml() {
        return WmsXmlWriter.capabilities(
                recordService.buildPublicUrl("/gis/wms"), catalog.listPublished());
    }

    /** 生成 WMS 异常报告文档。 */
    public String exceptionXml(OgcException exception) {
        return WmsXmlWriter.serviceExceptionReport(
                exception.code(), exception.locator(), exception.getMessage());
    }

    /** 解析并校验 GetMap 请求，同时把所有图层解析为可渲染单元。 */
    private WmsMapRequest parseMapRequest(Map<String, String> parameters) {
        List<String> layers = splitList(required(parameters.get("layers"), "LAYERS"));
        List<String> styles = splitList(parameters.get("styles"));
        String crs = requireCrs(parameters);
        double[] bbox = parseBbox(required(parameters.get("bbox"), "BBOX"), crs, version(parameters));
        int width = parseInt(required(parameters.get("width"), "WIDTH"), "WIDTH");
        int height = parseInt(required(parameters.get("height"), "HEIGHT"), "HEIGHT");
        String format = requireFormat(parameters.get("format"));
        boolean transparent = "TRUE".equalsIgnoreCase(trimToNull(parameters.get("transparent")));
        Color bgcolor = parseColor(parameters.get("bgcolor"));
        try {
            return new WmsMapRequest(layers, styles, crs, bbox, width, height,
                    format, transparent, bgcolor);
        } catch (IllegalArgumentException ex) {
            throw new OgcException("InvalidParameterValue", null, ex.getMessage());
        }
    }

    /**
     * 解析并校验 GetFeatureInfo 请求。
     *
     * <p>参数名遵循 GeoServer：1.3.0 使用 {@code I}/{@code J}，1.1.1 使用 {@code X}/{@code Y}；
     * {@code INFO_FORMAT} 缺省为 {@code text/plain}。</p>
     */
    private InfoRequest parseInfoRequest(Map<String, String> parameters) {
        List<String> queryLayers = splitList(
                required(parameters.get("query_layers"), "QUERY_LAYERS"));
        String crs = requireCrs(parameters);
        double[] bbox = parseBbox(required(parameters.get("bbox"), "BBOX"), crs, version(parameters));
        int width = parseInt(required(parameters.get("width"), "WIDTH"), "WIDTH");
        int height = parseInt(required(parameters.get("height"), "HEIGHT"), "HEIGHT");
        boolean legacy = LEGACY_VERSION.equals(version(parameters));
        int column = parseIndex(required(parameters.get(legacy ? "x" : "i"),
                legacy ? "X" : "I"), legacy ? "X" : "I", width - 1);
        int row = parseIndex(required(parameters.get(legacy ? "y" : "j"),
                legacy ? "Y" : "J"), legacy ? "Y" : "J", height - 1);
        int buffer = parseBuffer(parameters.get("buffer"));
        String infoFormat = requireInfoFormat(parameters.get("info_format"));
        // FEATURE_COUNT 由 GeoServer 用于限制返回要素数量；栅格每个查询点固定返回一条，读取后忽略
        parseFeatureCount(parameters.get("feature_count"));
        return new InfoRequest(queryLayers, crs, bbox, width, height,
                column, row, buffer, infoFormat);
    }

    /**
     * 逐图层查询点击位置的原始像元值。
     *
     * <p>结果顺序与 {@code QUERY_LAYERS} 一致；图层无数据时输出只有表头的空结果，
     * 与 GeoServer 的行为相同。</p>
     */
    private WmsReply queryInfo(InfoRequest request) {
        CoordinateReferenceSystem queryCrs = decodeCrs(request.crs());
        double[] bbox = request.bbox();
        double pixelSpanX = (bbox[2] - bbox[0]) / request.width();
        double pixelSpanY = (bbox[3] - bbox[1]) / request.height();
        // 像素中心取 (I + 0.5, J + 0.5)；图像行号自上而下，因此纵坐标从 maxY 递减
        double worldX = bbox[0] + (request.column() + 0.5) * pixelSpanX;
        double worldY = bbox[3] - (request.row() + 0.5) * pixelSpanY;

        List<WmsInfoWriter.FeatureInfo> features = new ArrayList<>();
        for (String layerCode : request.queryLayers()) {
            PublishedImageryCatalog.PublishedLayer published = requirePublishedLayer(
                    layerCode, "QUERY_LAYERS");
            LayerSources layer = resolveSources(layerCode, "QUERY_LAYERS");
            WmsPixelSampler.BandSample sample = layer.sources().isEmpty() ? null
                    : pixelSampler.sample(layer.sources(), queryCrs, worldX, worldY, request.buffer());
            if (sample != null) {
                features.add(toFeatureInfo(published.serviceCode(), sample));
            }
        }
        String mediaType = INFO_JSON.equals(request.infoFormat()) ? INFO_JSON : INFO_TEXT;
        String body = INFO_JSON.equals(mediaType)
                ? infoWriter.geojson(features) : infoWriter.text(features);
        // 响应头声明字符集，避免中文属性在客户端按 ISO-8859-1 解码
        return new WmsReply(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                mediaType + ";charset=UTF-8");
    }

    private WmsInfoWriter.FeatureInfo toFeatureInfo(String typeName, WmsPixelSampler.BandSample sample) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int band = 0; band < sample.bandNames().length; band++) {
            properties.put(sample.bandNames()[band], sample.values()[band]);
        }
        return new WmsInfoWriter.FeatureInfo(
                typeName, sample.longitude(), sample.latitude(), properties);
    }

    private WmsReply renderMap(WmsMapRequest request) {
        BufferedImage merged = null;
        try {
            merged = new BufferedImage(request.width(), request.height(),
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = merged.createGraphics();
            try {
                if (!request.transparent()) {
                    graphics.setColor(request.bgcolor() == null ? Color.WHITE : request.bgcolor());
                    graphics.fillRect(0, 0, request.width(), request.height());
                }
                // 多图层按请求顺序叠放，后画的在前
                for (String layerCode : request.layers()) {
                    RenderingUnit unit = resolveRenderingUnit(layerCode, request);
                    if (unit == null) {
                        continue;
                    }
                    BufferedImage layerImage = rasterRenderer.render(
                            unit.sources(), unit.bounds(), request);
                    graphics.drawImage(layerImage, 0, 0, null);
                    layerImage.flush();
                }
            } finally {
                graphics.dispose();
            }
            return imageReply(merged, request.format());
        } catch (OgcException ex) {
            if (merged != null) {
                merged.flush();
            }
            throw ex;
        } catch (Exception ex) {
            if (merged != null) {
                merged.flush();
            }
            log.error("[OGC][WMS] 渲染地图失败", ex);
            throw new OgcException("NoApplicableCode", null, "渲染地图失败: " + ex.getMessage());
        }
    }

    /** 把图层编码解析成「源文件 + 请求坐标系下的范围」；图层不存在时抛出 OGC 异常。 */
    private RenderingUnit resolveRenderingUnit(String layerCode, WmsMapRequest request) {
        PublishedImageryCatalog.PublishedLayer published = requirePublishedLayer(layerCode, "LAYERS");
        LayerSources layer = resolveSources(layerCode, "LAYERS");
        if (layer.sources().isEmpty()) {
            // 源数据已不可用时不阻断整张图，跳过该图层
            log.warn("[OGC][WMS] 图层 {} 没有可用的源数据，已跳过", layerCode);
            return null;
        }
        try {
            CoordinateReferenceSystem targetCrs = decodeCrs(request.crs());
            double[] b = published.wgs84Bounds();
            ReferencedEnvelope wgs84 = new ReferencedEnvelope(
                    b[0], b[2], b[1], b[3], CRS.decode(WGS84, true));
            ReferencedEnvelope bounds = wgs84.transform(targetCrs, true);
            return new RenderingUnit(layer.sources(), bounds);
        } catch (Exception ex) {
            throw new OgcException("InvalidCRS", "CRS",
                    "无法转换图层坐标系: " + request.crs());
        }
    }

    /** 校验图层已发布且元数据可读，返回对外元数据。 */
    private PublishedImageryCatalog.PublishedLayer requirePublishedLayer(
            String layerCode, String locator) {
        PublishedImageryCatalog.PublishedLayer published = catalog.load(layerCode);
        if (published == null) {
            throw new OgcException("LayerNotDefined", locator,
                    "图层不存在或已停用: " + layerCode);
        }
        return published;
    }

    /** 定位图层的源 GeoTIFF；源文件全部不可用时返回空列表，由调用方决定降级方式。 */
    private LayerSources resolveSources(String layerCode, String locator) {
        try {
            var publication = recordService.getRequired(GisProcessingType.IMAGERY, layerCode);
            var tileSet = recordService.getPublicationTileSet(publication);
            return new LayerSources(sourceResolver.resolveSources(tileSet));
        } catch (RuntimeException ex) {
            throw new OgcException("LayerNotDefined", locator,
                    "图层不存在或已停用: " + layerCode);
        }
    }

    private CoordinateReferenceSystem decodeCrs(String crs) {
        try {
            return CRS.decode(crs, true);
        } catch (Exception ex) {
            throw new OgcException("InvalidCRS", "CRS", "不支持的坐标系: " + crs);
        }
    }

    /** 输出编码：仅支持 PNG 与 JPEG，与 GeoServer 默认能力一致。 */
    private WmsReply imageReply(BufferedImage image, String format) {
        String extension = switch (format.toLowerCase(Locale.ROOT)) {
            case "image/png", "png" -> "png";
            case "image/jpeg", "image/jpg", "jpeg", "jpg" -> "jpg";
            default -> throw new OgcException("InvalidFormat", "FORMAT",
                    "FORMAT 仅支持 image/png 或 image/jpeg: " + format);
        };
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            BufferedImage encoded = image;
            // JPEG 不支持透明通道，转成 RGB 并铺白底
            if ("jpg".equals(extension) && image.getType() != BufferedImage.TYPE_INT_RGB) {
                encoded = new BufferedImage(image.getWidth(), image.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = encoded.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                    graphics.drawImage(image, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
            }
            if (!javax.imageio.ImageIO.write(encoded, extension, buffer)) {
                throw new OgcException("NoApplicableCode", null,
                        "当前 JVM 没有可用的影像编码器: " + extension);
            }
            if (encoded != image) {
                encoded.flush();
            }
            return new WmsReply(buffer.toByteArray(), "jpg".equals(extension)
                    ? "image/jpeg" : "image/png");
        } catch (java.io.IOException ex) {
            throw new OgcException("NoApplicableCode", null, "编码地图图片失败");
        }
    }

    private void requireService(Map<String, String> parameters) {
        String service = parameters.get("service");
        if (service != null && !service.isBlank()
                && !SERVICE_TYPE.equalsIgnoreCase(service.trim())) {
            throw new OgcException("InvalidParameterValue", "SERVICE", "SERVICE 仅支持 WMS");
        }
        String version = parameters.get("version");
        if (version != null && !version.isBlank()
                && !VERSION.equals(version.trim())
                && !LEGACY_VERSION.equals(version.trim())) {
            throw new OgcException("InvalidParameterValue", "VERSION",
                    "VERSION 仅支持 1.3.0 或 1.1.1");
        }
    }

    /** 规范后的请求版本；缺省按 1.3.0 处理。 */
    private String version(Map<String, String> parameters) {
        String version = trimToNull(parameters.get("version"));
        return version == null ? VERSION : version;
    }

    /** WMS 1.3.0 使用 CRS，WMS 1.1.1 使用 SRS，两者都接受。 */
    private String requireCrs(Map<String, String> parameters) {
        String crs = trimToNull(parameters.get("crs"));
        if (crs == null) {
            crs = trimToNull(parameters.get("srs"));
        }
        if (crs == null) {
            throw new OgcException("MissingParameterValue", "CRS", "缺少 CRS（或 SRS）参数");
        }
        try {
            CRS.decode(crs, true);
        } catch (Exception ex) {
            throw new OgcException("InvalidCRS", "CRS", "不支持的坐标系: " + crs);
        }
        return crs;
    }

    /**
     * 解析 BBOX 并统一规整成 (minX, minY, maxX, maxY)。
     *
     * <p>WMS 1.3.0 规定 EPSG:4326 的轴顺序为纬度在前（北向），CRS:84 与投影坐标系为横坐标在前；
     * WMS 1.1.1 始终使用经度在前，因此不做轴序交换。</p>
     */
    private double[] parseBbox(String value, String crs, String version) {
        String[] parts = value.split(",");
        if (parts.length != 4) {
            throw new OgcException("InvalidParameterValue", "BBOX",
                    "BBOX 必须包含4个逗号分隔的数值: " + value);
        }
        double[] numbers = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                numbers[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ex) {
                throw new OgcException("InvalidParameterValue", "BBOX",
                        "BBOX 包含非法数值: " + parts[i]);
            }
        }
        boolean latLonAxis = VERSION.equals(version) && "EPSG:4326".equalsIgnoreCase(crs.trim());
        if (latLonAxis) {
            // 纬度在前：minLat,minLon,maxLat,maxLon
            return new double[]{numbers[1], numbers[0], numbers[3], numbers[2]};
        }
        return numbers;
    }

    private String requireFormat(String format) {
        if (format == null || format.isBlank()) {
            throw new OgcException("MissingParameterValue", "FORMAT", "缺少 FORMAT 参数");
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (!List.of("image/png", "image/jpeg", "image/jpg",
                "png", "jpeg", "jpg").contains(normalized)) {
            throw new OgcException("InvalidFormat", "FORMAT",
                    "FORMAT 仅支持 image/png 或 image/jpeg: " + format);
        }
        return normalized;
    }

    private Color parseColor(String value) {
        String hex = trimToNull(value);
        if (hex == null) {
            return Color.WHITE;
        }
        String normalized = hex.startsWith("0x") || hex.startsWith("0X")
                ? hex.substring(2) : hex;
        if (!normalized.matches("[0-9A-Fa-f]{6}")) {
            return Color.WHITE;
        }
        return new Color(Integer.parseInt(normalized, 16));
    }

    /** 图层与样式列表按逗号分隔，空白项忽略。 */
    private List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    /** KVP 参数名不区分大小写，统一按键名小写读取。 */
    private Map<String, String> lowerCaseKeys(Map<String, String> requestParameters) {
        Map<String, String> parameters = new HashMap<>();
        if (requestParameters != null) {
            requestParameters.forEach((key, value) -> {
                if (key != null && value != null) {
                    parameters.put(key.toLowerCase(Locale.ROOT), value);
                }
            });
        }
        return parameters;
    }

    private int parseInt(String value, String locator) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < WmsMapRequest.MIN_DIMENSION || parsed > WmsMapRequest.MAX_DIMENSION) {
                throw new OgcException("InvalidParameterValue", locator,
                        locator + " 必须在 1 到 " + WmsMapRequest.MAX_DIMENSION + " 之间: " + value);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new OgcException("InvalidParameterValue", locator,
                    locator + " 必须为整数: " + value);
        }
    }

    /** 查询像素坐标必须落在图像范围内，与 GeoServer 的索引边界一致。 */
    private int parseIndex(String value, String locator, int maximum) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new OgcException("InvalidParameterValue", locator,
                    locator + " 必须为整数: " + value);
        }
        if (parsed < 0 || parsed > maximum) {
            throw new OgcException("InvalidParameterValue", locator,
                    locator + " 必须在 0 到 " + maximum + " 之间: " + value);
        }
        return parsed;
    }

    private int parseBuffer(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(normalized);
            if (parsed < 0 || parsed > MAX_BUFFER) {
                throw new OgcException("InvalidParameterValue", "BUFFER",
                        "BUFFER 必须在 0 到 " + MAX_BUFFER + " 之间: " + value);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new OgcException("InvalidParameterValue", "BUFFER",
                    "BUFFER 必须为整数: " + value);
        }
    }

    /** INFO_FORMAT 缺省为 text/plain，仅接受能力文档声明的两种格式。 */
    private String requireInfoFormat(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return INFO_TEXT;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (INFO_TEXT.equals(lower) || INFO_JSON.equals(lower)) {
            return lower;
        }
        throw new OgcException("InvalidFormat", "INFO_FORMAT",
                "INFO_FORMAT 仅支持 text/plain 或 application/json: " + value);
    }

    /** FEATURE_COUNT 只做格式校验；栅格图层每个查询点固定返回一条结果。 */
    private void parseFeatureCount(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return;
        }
        try {
            if (Integer.parseInt(normalized) < 1) {
                throw new NumberFormatException("not positive");
            }
        } catch (NumberFormatException ex) {
            throw new OgcException("InvalidParameterValue", "FEATURE_COUNT",
                    "FEATURE_COUNT 必须为正整数: " + value);
        }
    }

    private String required(String value, String locator) {
        if (value == null || value.isBlank()) {
            throw new OgcException("MissingParameterValue", locator, "缺少 " + locator + " 参数");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private WmsReply document(String xml) {
        return new WmsReply(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/xml;charset=UTF-8");
    }

    /** 单个图层的渲染输入。 */
    private record RenderingUnit(List<Path> sources, ReferencedEnvelope bounds) {
    }

    /** 单个图层的源 GeoTIFF；全部不可用时为空列表。 */
    private record LayerSources(List<Path> sources) {
    }

    /**
     * 结构化的 GetFeatureInfo 请求参数。
     *
     * @param queryLayers 查询图层，按请求顺序逐层输出结果
     * @param crs         请求坐标系
     * @param bbox        范围数组：[minX, minY, maxX, maxY]，顺序已按版本与 CRS 规整
     * @param width       图像像素宽度
     * @param height      图像像素高度
     * @param column      点击像素列号（0 起），1.3.0 为 I，1.1.1 为 X
     * @param row         点击像素行号（0 起），1.3.0 为 J，1.1.1 为 Y
     * @param buffer      以输出像素为单位的查询容差
     * @param infoFormat  响应格式：text/plain 或 application/json
     */
    public record InfoRequest(
            List<String> queryLayers,
            String crs,
            double[] bbox,
            int width,
            int height,
            int column,
            int row,
            int buffer,
            String infoFormat) {
    }

    /** WMS 响应内容：能力文档、异常报告、出图结果与要素信息统一以字节与媒体类型返回。 */
    public record WmsReply(byte[] body, String mediaType) {
    }

    /** WMS 请求参数或渲染失败，由控制层转换为 ServiceExceptionReport。 */
    public static class OgcException extends RuntimeException {

        private final String code;
        private final String locator;

        public OgcException(String code, String locator, String message) {
            super(message);
            this.code = code;
            this.locator = locator;
        }

        public String code() {
            return code;
        }

        public String locator() {
            return locator;
        }

        /** 服务内部故障返回 500，其余客户端错误返回 400。 */
        public boolean serverError() {
            return "NoApplicableCode".equals(code);
        }
    }
}
