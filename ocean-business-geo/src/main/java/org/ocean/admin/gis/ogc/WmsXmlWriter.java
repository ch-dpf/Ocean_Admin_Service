package org.ocean.admin.gis.ogc;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * WMS 1.3.0 能力文档与异常报告的 XML 输出（OGC 06-042 标准）。
 *
 * <p>元素层级遵循 {@code capabilities_1_3_0.xsd}，使用 {@code http://www.opengis.net/wms}
 * 默认命名空间，通用元素属于 {@code http://www.opengis.net/ows} 或 XLink。</p>
 */
public final class WmsXmlWriter {

    static final String NAMESPACE = "http://www.opengis.net/wms";
    static final String XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";
    static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
    static final String SCHEMA_LOCATION =
            "http://www.opengis.net/wms http://schemas.opengis.net/wms/1.3.0/capabilities_1_3_0.xsd";

    private WmsXmlWriter() {
    }

    /**
     * 生成 WMS 1.3.0 GetCapabilities 响应 XML。
     *
     * @param serviceUrl WMS 服务基准地址（如 http://host:8090/gis/wms）
     * @param layers     已发布图层列表
     */
    public static String capabilities(String serviceUrl,
            List<PublishedImageryCatalog.PublishedLayer> layers,
            List<PublishedVectorCatalog.PublishedVectorLayer> vectorLayers) {
        String baseUrl = stripTrailingSlash(serviceUrl);
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("", "WMS_Capabilities", NAMESPACE);
            writer.writeDefaultNamespace(NAMESPACE);
            writer.writeNamespace("xlink", XLINK_NAMESPACE);
            writer.writeNamespace("xsi", XSI_NAMESPACE);
            writer.writeAttribute("xsi", XSI_NAMESPACE, "schemaLocation", SCHEMA_LOCATION);
            writer.writeAttribute("version", "1.3.0");

            writeService(writer);
            writeCapability(writer, baseUrl, layers, vectorLayers);

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 WMS 能力文档失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    /**
     * 生成 WMS 1.3.0 异常报告 XML。
     *
     * @param code    异常代码，如 InvalidFormat、InvalidCRS、LayerNotDefined
     * @param locator 出错参数名，可为空
     * @param message 错误说明
     */
    public static String serviceExceptionReport(String code, String locator, String message) {
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("", "ServiceExceptionReport", NAMESPACE);
            writer.writeDefaultNamespace(NAMESPACE);
            writer.writeAttribute("version", "1.3.0");
            writer.writeStartElement("", "ServiceException", NAMESPACE);
            if (code != null && !code.isBlank()) {
                writer.writeAttribute("code", code);
            }
            if (locator != null && !locator.isBlank()) {
                writer.writeAttribute("locator", locator);
            }
            writer.writeCharacters(message == null ? "" : message);
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 WMS 异常报告失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    private static void writeService(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("", "Service", NAMESPACE);
        writeText(writer, "Name", "WMS");
        writeText(writer, "Title", "Ocean Admin Web Map Service");
        writeText(writer, "Abstract", "Ocean Admin 已发布栅格图层动态地图服务 (WMS 1.3.0)");
        writeText(writer, "Fees", "NONE");
        writeText(writer, "AccessConstraints", "NONE");
        writer.writeEndElement();
    }

    private static void writeCapability(XMLStreamWriter writer, String baseUrl,
            List<PublishedImageryCatalog.PublishedLayer> layers,
            List<PublishedVectorCatalog.PublishedVectorLayer> vectorLayers)
            throws XMLStreamException {
        writer.writeStartElement("", "Capability", NAMESPACE);

        writer.writeStartElement("", "Request", NAMESPACE);
        writeOperation(writer, "GetCapabilities", baseUrl);
        writeOperation(writer, "GetMap", baseUrl);
        writeOperation(writer, "GetFeatureInfo", baseUrl);
        writer.writeEndElement(); // Request

        writer.writeStartElement("", "Exception", NAMESPACE);
        writeText(writer, "Format", "XML");
        writeText(writer, "Format", "INIMAGE");
        writer.writeEndElement(); // Exception

        // 顶层 Root Layer 容纳所有已发布图层（GeoServer 经典结构）
        writer.writeStartElement("", "Layer", NAMESPACE);
        writeText(writer, "Title", "Ocean Admin Root Layer");
        writeSupportedCrs(writer);

        for (PublishedImageryCatalog.PublishedLayer layer : layers) {
            writeLayer(writer, layer);
        }
        for (PublishedVectorCatalog.PublishedVectorLayer layer : vectorLayers) {
            writeVectorLayer(writer, layer);
        }

        writer.writeEndElement(); // Root Layer
        writer.writeEndElement(); // Capability
    }

    /** 矢量图层使用发布样式方案作为 WMS Style；文件组已在该图层内合并渲染。 */
    private static void writeVectorLayer(XMLStreamWriter writer,
            PublishedVectorCatalog.PublishedVectorLayer layer) throws XMLStreamException {
        writer.writeStartElement("", "Layer", NAMESPACE);
        writer.writeAttribute("queryable", "1");
        writeText(writer, "Name", layer.serviceCode());
        writeText(writer, "Title", layer.serviceName());
        writeText(writer, "Abstract", "已发布矢量服务（全部文件组合并渲染）: " + layer.serviceCode());
        writeSupportedCrs(writer);

        double[] b = layer.wgs84Bounds();
        writer.writeStartElement("", "EX_GeographicBoundingBox", NAMESPACE);
        writeText(writer, "westBoundLongitude", Double.toString(b[0]));
        writeText(writer, "eastBoundLongitude", Double.toString(b[2]));
        writeText(writer, "southBoundLatitude", Double.toString(b[1]));
        writeText(writer, "northBoundLatitude", Double.toString(b[3]));
        writer.writeEndElement();

        writeBoundingBox(writer, "CRS:84", b[0], b[1], b[2], b[3]);
        writeBoundingBox(writer, "EPSG:3857", b[0] * 20037508.342789244 / 180d,
                projectMercatorY(b[1]), b[2] * 20037508.342789244 / 180d,
                projectMercatorY(b[3]));
        for (PublishedVectorCatalog.StyleOption style : layer.styles()) {
            writer.writeStartElement("", "Style", NAMESPACE);
            if (style.id().equals(layer.styleId())) {
                writer.writeAttribute("isDefault", "true");
            }
            writeText(writer, "Name", style.id());
            writeText(writer, "Title", style.name());
            writer.writeEndElement();
        }
        writer.writeEndElement();
    }

    private static void writeOperation(XMLStreamWriter writer, String name, String baseUrl)
            throws XMLStreamException {
        writer.writeStartElement("", name, NAMESPACE);
        if ("GetCapabilities".equals(name)) {
            writeText(writer, "Format", "text/xml");
        } else if ("GetMap".equals(name)) {
            writeText(writer, "Format", "image/png");
            writeText(writer, "Format", "image/jpeg");
        } else if ("GetFeatureInfo".equals(name)) {
            writeText(writer, "Format", WmsInfoWriter.TEXT);
            writeText(writer, "Format", WmsInfoWriter.JSON);
        }
        writer.writeStartElement("", "DCPType", NAMESPACE);
        writer.writeStartElement("", "HTTP", NAMESPACE);
        writer.writeStartElement("", "Get", NAMESPACE);
        writer.writeStartElement("", "OnlineResource", NAMESPACE);
        writer.writeAttribute("xlink", XLINK_NAMESPACE, "type", "simple");
        writer.writeAttribute("xlink", XLINK_NAMESPACE, "href", baseUrl + "?");
        writer.writeEndElement(); // OnlineResource
        writer.writeEndElement(); // Get
        writer.writeEndElement(); // HTTP
        writer.writeEndElement(); // DCPType
        writer.writeEndElement(); // name
    }

    private static void writeSupportedCrs(XMLStreamWriter writer) throws XMLStreamException {
        writeText(writer, "CRS", "EPSG:3857");
        writeText(writer, "CRS", "EPSG:4326");
        writeText(writer, "CRS", "CRS:84");
    }

    private static void writeLayer(XMLStreamWriter writer,
            PublishedImageryCatalog.PublishedLayer layer) throws XMLStreamException {
        writer.writeStartElement("", "Layer", NAMESPACE);
        writer.writeAttribute("queryable", "1");
        writeText(writer, "Name", layer.serviceCode());
        writeText(writer, "Title", catalogTitle(layer));
        writeText(writer, "Abstract", "已发布 OGC 服务: " + layer.serviceCode());
        writeSupportedCrs(writer);

        double[] b = layer.wgs84Bounds();
        // WMS 1.3.0 的 EX_GeographicBoundingBox，顺序: westBoundLongitude, eastBoundLongitude, southBoundLatitude, northBoundLatitude
        writer.writeStartElement("", "EX_GeographicBoundingBox", NAMESPACE);
        writeText(writer, "westBoundLongitude", Double.toString(b[0]));
        writeText(writer, "eastBoundLongitude", Double.toString(b[2]));
        writeText(writer, "southBoundLatitude", Double.toString(b[1]));
        writeText(writer, "northBoundLatitude", Double.toString(b[3]));
        writer.writeEndElement(); // EX_GeographicBoundingBox

        // 默认 CRS:84 BoundingBox (经度在前，纬度在后)
        writeBoundingBox(writer, "CRS:84", b[0], b[1], b[2], b[3]);

        // EPSG:3857 BoundingBox
        double minX = b[0] * 20037508.342789244 / 180d;
        double maxX = b[2] * 20037508.342789244 / 180d;
        double minY = projectMercatorY(b[1]);
        double maxY = projectMercatorY(b[3]);
        writeBoundingBox(writer, "EPSG:3857", minX, minY, maxX, maxY);

        // Style
        writer.writeStartElement("", "Style", NAMESPACE);
        writeText(writer, "Name", "default");
        writeText(writer, "Title", "Default Style");
        writer.writeEndElement();

        writer.writeEndElement(); // Layer
    }

    private static void writeBoundingBox(XMLStreamWriter writer, String crs,
            double minx, double miny, double maxx, double maxy) throws XMLStreamException {
        writer.writeStartElement("", "BoundingBox", NAMESPACE);
        writer.writeAttribute("CRS", crs);
        writer.writeAttribute("minx", Double.toString(minx));
        writer.writeAttribute("miny", Double.toString(miny));
        writer.writeAttribute("maxx", Double.toString(maxx));
        writer.writeAttribute("maxy", Double.toString(maxy));
        writer.writeEndElement();
    }

    private static double projectMercatorY(double lat) {
        double clamped = Math.max(-85.05112877980659, Math.min(85.05112877980659, lat));
        double rad = Math.toRadians(clamped);
        return Math.log(Math.tan(Math.PI / 4d + rad / 2d)) * 20037508.342789244 / Math.PI;
    }

    private static void writeText(XMLStreamWriter writer, String localName, String value)
            throws XMLStreamException {
        writer.writeStartElement("", localName, NAMESPACE);
        writer.writeCharacters(value == null ? "" : value);
        writer.writeEndElement();
    }

    /** 能力文档展示名优先使用发布时填写的图层名称，缺失时回退服务编码。 */
    private static String catalogTitle(PublishedImageryCatalog.PublishedLayer layer) {
        String title = layer.layerName();
        return title == null || title.isBlank() ? layer.serviceCode() : title;
    }

    private static XMLStreamWriter newWriter(StringWriter buffer) throws XMLStreamException {
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        return factory.createXMLStreamWriter(buffer);
    }

    private static void closeQuietly(XMLStreamWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (XMLStreamException ignored) {
            }
        }
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
