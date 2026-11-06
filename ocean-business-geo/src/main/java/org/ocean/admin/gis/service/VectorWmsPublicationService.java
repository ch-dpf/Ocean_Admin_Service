package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.entity.GisPublication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** WMS 协议与动态地图缓存；数据和样式固定在发布版本，原生绘图在独立进程执行。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorWmsPublicationService {
    private static final String WMS_NS = "http://www.opengis.net/wms";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final double HALF_WORLD = Math.PI * 6378137;
    private final VectorPublicationService publications;
    private final VectorResourceService resources;
    private final PublicationRecordService records;
    private final GeoPackageStore store;
    private final ObjectMapper json;
    private final VectorTileProperties limits;
    private final Semaphore renderSlots = new Semaphore(2, true);
    private final Semaphore queueSlots = new Semaphore(18, true);
    private final Map<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, CachedMap> cache = new LinkedHashMap<>(64, .75f, true);
    private long cacheBytes;

    public record Response(String xml, byte[] image, String contentType) { }
    private record CachedMap(byte[] body, long expiresAt) { }
    /** 范围统一成 x/y 顺序，协议版本的轴序差异不进入渲染器。 */
    public record MapRequest(String crs, double[] bounds, int width, int height, boolean transparent,
                             int background, String styleId) { }

    public Response handle(String code, MultiValueMap<String, String> query) {
        String version = "1.3.0";
        try {
            Map<String, String> parameters = parameters(query);
            version = parameters.getOrDefault("VERSION", "1.3.0");
            if (!Set.of("1.3.0", "1.1.1").contains(version)) throw error(version, "InvalidParameterValue", "不支持的 WMS 版本");
            if (!"WMS".equals(required(parameters, "SERVICE", version))) throw error(version, "InvalidParameterValue", "SERVICE 必须为 WMS");
            var publication = publications.get(code, true);
            if (!"WMS".equals(publication.getServiceProtocol())) throw error(version, "LayerNotDefined", "当前记录不是 WMS 服务");
            JsonNode binding = json.readTree(publication.getVectorDefinition());
            String operation = required(parameters, "REQUEST", version);
            if ("GetCapabilities".equals(operation)) {
                return new Response(capabilities(publication, binding, version), null,
                        "1.1.1".equals(version) ? "application/vnd.ogc.wms_xml" : "text/xml");
            }
            if (!"GetMap".equals(operation)) throw error(version, "OperationNotSupported", "仅支持 GetCapabilities 和 GetMap");
            required(parameters, "VERSION", version);
            MapRequest request = parseMap(code, binding, parameters, version);
            byte[] body = image(publication, binding, request);
            // 渲染期间也可能发生停用；响应前再检查一次。
            publications.get(code, true);
            return new Response(null, body, "image/png");
        } catch (WmsException ex) {
            throw ex;
        } catch (ResponseStatusException ex) {
            throw new WmsException(version, "LayerNotDefined", "服务不存在或已停用", HttpStatus.NOT_FOUND);
        } catch (IOException | IllegalStateException ex) {
            log.warn("WMS 地图读取失败，服务 {}", code, ex);
            throw new WmsException(version, "NoApplicableCode", "地图渲染暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static Map<String, String> parameters(MultiValueMap<String, String> query) {
        Map<String, String> result = new HashMap<>();
        query.forEach((key, values) -> {
            String name = key.toUpperCase(Locale.ROOT);
            if (values.size() != 1 || result.putIfAbsent(name, values.getFirst()) != null)
                throw error("1.3.0", "InvalidParameterValue", "参数重复: " + name);
        });
        return result;
    }

    /** 1.1.1 仅为已确认的客户端接入保留；停止支持该版本时删除本方法及能力文档中的版本分支。 */
    static MapRequest parseMap(String code, JsonNode binding, Map<String, String> parameters, String version) {
        boolean modern = "1.3.0".equals(version);
        if (!code.equals(required(parameters, "LAYERS", version))) throw error(version, "LayerNotDefined", "请使用能力文档中的图层名称");
        if (!"image/png".equals(required(parameters, "FORMAT", version))) throw error(version, "InvalidFormat", "仅支持 image/png");
        if (!parameters.containsKey("STYLES")) throw error(version, "MissingParameterValue", "缺少参数 STYLES");
        String styleId = parameters.get("STYLES");
        if (styleId.isEmpty() || "default".equals(styleId)) styleId = binding.path("defaultStyleId").asText();
        boolean found = false;
        for (JsonNode style : binding.path("styles")) if (styleId.equals(style.path("id").asText())) found = true;
        if (!found) throw error(version, "StyleNotDefined", "样式不属于该发布服务");
        String crs = required(parameters, modern ? "CRS" : "SRS", version).toUpperCase(Locale.ROOT);
        if (!Set.of("EPSG:3857", "EPSG:4326", "CRS:84").contains(crs) || !modern && "CRS:84".equals(crs))
            throw error(version, modern ? "InvalidCRS" : "InvalidSRS", "不支持的坐标系");
        double[] bounds;
        try {
            String[] fields = required(parameters, "BBOX", version).split(",", -1);
            if (fields.length != 4) throw new NumberFormatException();
            bounds = Arrays.stream(fields).mapToDouble(Double::parseDouble).toArray();
            for (double value : bounds) if (!Double.isFinite(value)) throw new NumberFormatException();
        } catch (NumberFormatException ex) { throw error(version, "InvalidParameterValue", "BBOX 必须包含四个有限数值"); }
        if (modern && "EPSG:4326".equals(crs)) bounds = new double[]{bounds[1], bounds[0], bounds[3], bounds[2]};
        boolean mercator = "EPSG:3857".equals(crs);
        double maxX = mercator ? HALF_WORLD : 180, maxY = mercator ? HALF_WORLD : 90;
        if (bounds[0] >= bounds[2] || bounds[1] >= bounds[3] || bounds[0] < -maxX - 1e-8
                || bounds[2] > maxX + 1e-8 || bounds[1] < -maxY - 1e-8 || bounds[3] > maxY + 1e-8)
            throw error(version, "InvalidParameterValue", "BBOX 超出坐标系范围或边界顺序错误");
        int width = dimension(parameters, "WIDTH", version), height = dimension(parameters, "HEIGHT", version);
        String transparent = parameters.getOrDefault("TRANSPARENT", "FALSE");
        if (!"TRUE".equalsIgnoreCase(transparent) && !"FALSE".equalsIgnoreCase(transparent))
            throw error(version, "InvalidParameterValue", "TRANSPARENT 必须为 TRUE 或 FALSE");
        String background = parameters.getOrDefault("BGCOLOR", "0xFFFFFF");
        if (!background.matches("(?i)0x[0-9a-f]{6}")) throw error(version, "InvalidParameterValue", "BGCOLOR 必须为 0xRRGGBB");
        String exceptions = parameters.getOrDefault("EXCEPTIONS", modern ? "XML" : "application/vnd.ogc.se_xml");
        if (!(modern ? "XML" : "application/vnd.ogc.se_xml").equals(exceptions))
            throw error(version, "InvalidFormat", "仅支持 XML 异常响应");
        return new MapRequest(mercator ? "EPSG:3857" : "EPSG:4326", bounds, width, height,
                Boolean.parseBoolean(transparent), Integer.parseInt(background.substring(2), 16), styleId);
    }

    private byte[] image(GisPublication publication, JsonNode binding, MapRequest request) throws IOException {
        String key = publication.getVectorResourceId() + ":" + binding.path("releaseVersion").asText() + ":"
                + request.styleId() + ":" + request.crs() + ":" + Arrays.toString(request.bounds()) + ":"
                + request.width() + ":" + request.height() + ":" + request.transparent() + ":" + request.background();
        synchronized (cache) {
            CachedMap existing = cache.get(key);
            if (existing != null && existing.expiresAt() > System.nanoTime()) return existing.body();
            if (existing != null) { cache.remove(key); cacheBytes -= existing.body().length; }
        }
        var future = new CompletableFuture<byte[]>();
        var underway = pending.putIfAbsent(key, future);
        if (underway != null) {
            try { return underway.get(45, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("地图请求已中断", ex); }
            catch (Exception ex) { throw new IOException("等待地图渲染失败", ex); }
        }
        boolean acquired = false;
        boolean queued = false;
        try {
            queued = queueSlots.tryAcquire();
            if (!queued) throw new IOException("动态地图请求队列已满");
            try { acquired = renderSlots.tryAcquire(10, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("地图请求已中断", ex); }
            if (!acquired) throw new IOException("动态地图渲染繁忙");
            var resource = resources.require(publication.getVectorResourceId());
            JsonNode scheme = null;
            for (JsonNode item : binding.path("styles")) if (request.styleId().equals(item.path("id").asText())) scheme = item;
            if (scheme == null) throw new IOException("发布样式不存在");
            byte[] bytes = store.map(resource.getStorageKey(), json.readTree(resource.getDefinitionJson()), scheme,
                    request.crs(), request.bounds(), request.width(), request.height(), request.transparent(), request.background());
            synchronized (cache) {
                CachedMap previous = cache.put(key, new CachedMap(bytes, System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(limits.getTileCacheTtlSeconds())));
                cacheBytes += bytes.length - (previous == null ? 0 : previous.body().length);
                while (cacheBytes > limits.getMaxLocalCacheBytes() || cache.size() > limits.getMaxCachedTiles())
                    cacheBytes -= cache.pollFirstEntry().getValue().body().length;
            }
            future.complete(bytes);
            return bytes;
        } catch (IOException | RuntimeException ex) {
            future.completeExceptionally(ex);
            throw ex;
        } finally {
            pending.remove(key, future);
            if (acquired) renderSlots.release();
            if (queued) queueSlots.release();
        }
    }

    private String capabilities(GisPublication publication, JsonNode binding, String version) {
        boolean modern = "1.3.0".equals(version);
        try {
            var output = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement(modern ? "WMS_Capabilities" : "WMT_MS_Capabilities");
            if (modern) xml.writeDefaultNamespace(WMS_NS);
            if (modern) xml.writeNamespace("xlink", XLINK_NS);
            xml.writeAttribute("version", version);
            xml.writeStartElement("Service");
            element(xml, "Name", modern ? "WMS" : "OGC:WMS");
            element(xml, "Title", publication.getServiceName());
            String url = records.buildServiceRoot(org.ocean.admin.gis.processing.GisProcessingType.VECTOR, publication.getServiceCode()) + "wms?";
            online(xml, url);
            element(xml, "Fees", "none"); element(xml, "AccessConstraints", "none");
            if (modern) { element(xml, "MaxWidth", "2048"); element(xml, "MaxHeight", "2048"); }
            xml.writeEndElement();
            xml.writeStartElement("Capability"); xml.writeStartElement("Request");
            for (String operation : new String[]{"GetCapabilities", "GetMap"}) {
                xml.writeStartElement(operation);
                element(xml, "Format", "GetMap".equals(operation) ? "image/png" : modern ? "text/xml" : "application/vnd.ogc.wms_xml");
                xml.writeStartElement("DCPType"); xml.writeStartElement("HTTP"); xml.writeStartElement("Get");
                online(xml, url);
                xml.writeEndElement(); xml.writeEndElement(); xml.writeEndElement(); xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeStartElement("Exception"); element(xml, "Format", modern ? "XML" : "application/vnd.ogc.se_xml"); xml.writeEndElement();
            xml.writeStartElement("Layer"); xml.writeAttribute("queryable", "0");
            element(xml, "Name", publication.getServiceCode()); element(xml, "Title", publication.getServiceName());
            for (String crs : modern ? new String[]{"EPSG:3857", "EPSG:4326", "CRS:84"} : new String[]{"EPSG:3857", "EPSG:4326"})
                element(xml, modern ? "CRS" : "SRS", crs);
            if (modern) {
                xml.writeStartElement("EX_GeographicBoundingBox");
                element(xml, "westBoundLongitude", "-180"); element(xml, "eastBoundLongitude", "180");
                element(xml, "southBoundLatitude", "-85.05112878"); element(xml, "northBoundLatitude", "85.05112878");
                xml.writeEndElement();
            } else {
                xml.writeEmptyElement("LatLonBoundingBox");
                bounds(xml, -180, -85.05112878, 180, 85.05112878);
            }
            xml.writeEmptyElement("BoundingBox"); xml.writeAttribute(modern ? "CRS" : "SRS", "EPSG:3857");
            bounds(xml, -HALF_WORLD, -HALF_WORLD, HALF_WORLD, HALF_WORLD);
            xml.writeEmptyElement("BoundingBox"); xml.writeAttribute(modern ? "CRS" : "SRS", "EPSG:4326");
            if (modern) bounds(xml, -85.05112878, -180, 85.05112878, 180);
            else bounds(xml, -180, -85.05112878, 180, 85.05112878);
            if (modern) {
                xml.writeEmptyElement("BoundingBox"); xml.writeAttribute("CRS", "CRS:84");
                bounds(xml, -180, -85.05112878, 180, 85.05112878);
            }
            xml.writeStartElement("Style"); element(xml, "Name", "default"); element(xml, "Title", "默认发布方案"); xml.writeEndElement();
            for (JsonNode scheme : binding.path("styles")) {
                xml.writeStartElement("Style"); element(xml, "Name", scheme.path("id").asText());
                element(xml, "Title", scheme.path("name").asText()); xml.writeEndElement();
            }
            xml.writeEndElement(); xml.writeEndElement(); xml.writeEndElement(); xml.writeEndDocument(); xml.close();
            return output.toString();
        } catch (Exception ex) { throw new IllegalStateException("WMS 能力文档生成失败", ex); }
    }

    private static void bounds(XMLStreamWriter xml, double minX, double minY, double maxX, double maxY) throws Exception {
        xml.writeAttribute("minx", Double.toString(minX)); xml.writeAttribute("miny", Double.toString(minY));
        xml.writeAttribute("maxx", Double.toString(maxX)); xml.writeAttribute("maxy", Double.toString(maxY));
    }
    private static void online(XMLStreamWriter xml, String url) throws Exception {
        xml.writeEmptyElement("OnlineResource");
        xml.writeNamespace("xlink", XLINK_NS);
        xml.writeAttribute("xlink", XLINK_NS, "type", "simple"); xml.writeAttribute("xlink", XLINK_NS, "href", url);
    }
    private static void element(XMLStreamWriter xml, String name, String value) throws Exception {
        xml.writeStartElement(name); xml.writeCharacters(value); xml.writeEndElement();
    }
    private static int dimension(Map<String, String> parameters, String key, String version) {
        String value = required(parameters, key, version);
        if (!value.matches("[0-9]{1,4}")) throw error(version, "InvalidParameterValue", key + " 无效");
        int result = Integer.parseInt(value);
        if (result < 1 || result > 2048) throw error(version, "InvalidParameterValue", key + " 必须为 1～2048");
        return result;
    }
    private static String required(Map<String, String> parameters, String key, String version) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) throw error(version, "MissingParameterValue", "缺少参数 " + key);
        return value;
    }
    private static WmsException error(String version, String code, String message) {
        return new WmsException(version, code, message, HttpStatus.BAD_REQUEST);
    }
    public static final class WmsException extends RuntimeException {
        public final String version;
        public final String code;
        public final HttpStatus status;
        public WmsException(String version, String code, String message, HttpStatus status) {
            super(message); this.version = version; this.code = code; this.status = status;
        }
    }
    public static String exceptionXml(WmsException error) {
        try {
            var output = new StringWriter();
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument("UTF-8", "1.0"); xml.writeStartElement("ServiceExceptionReport");
            if ("1.3.0".equals(error.version)) xml.writeDefaultNamespace("http://www.opengis.net/ogc");
            xml.writeAttribute("version", error.version);
            xml.writeStartElement("ServiceException"); xml.writeAttribute("code", error.code); xml.writeCharacters(error.getMessage());
            xml.writeEndElement(); xml.writeEndElement(); xml.writeEndDocument(); xml.close();
            return output.toString();
        } catch (Exception ex) { throw new IllegalStateException("WMS 异常文档生成失败", ex); }
    }
}
