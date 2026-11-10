package org.ocean.admin.gis.ogc;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * WFS 2.0.0 能力文档、要素类型描述与异常报告的 XML 输出。
 *
 * <p>能力文档遵循 OGC 09-025r1 的 wfs.xsd：根元素属于 WFS 2.0 命名空间，
 * 服务描述与操作元数据复用 OWS 1.1 通用元素。DescribeFeatureType 输出无外部
 * 命名空间依赖的宽松 XSD，供客户端核对字段，不绑定 GML 具体几何类型。</p>
 */
public final class WfsXmlWriter {

    static final String NAMESPACE = "http://www.opengis.net/wfs/2.0";
    static final String OWS_NAMESPACE = "http://www.opengis.net/ows/1.1";
    static final String XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";
    static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String XS_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private static final String DEFAULT_CRS = "urn:ogc:def:crs:OGC:1.3:CRS84";
    private static final String MERCATOR_CRS = "urn:ogc:def:crs:EPSG::3857";

    /** 能力文档标题优先使用 Title，其次 LayerName，最后回退服务编码。 */
    private static String displayTitle(String title, String layerName, String serviceCode) {
        if (title != null && !title.isBlank()) return title;
        if (layerName != null && !layerName.isBlank()) return layerName;
        return serviceCode;
    }

    /** 能力文档摘要优先使用 Abstract；缺失时用关键字或服务编码兜底。 */
    private static String displayAbstract(String abstractText, String keywords, String serviceCode) {
        if (abstractText != null && !abstractText.isBlank()) return abstractText;
        if (keywords != null && !keywords.isBlank()) return "关键字: " + keywords;
        return "已发布矢量数据集的全部冻结图层: " + serviceCode;
    }

    private WfsXmlWriter() {
    }

    /** 生成 GetCapabilities 响应；每个已发布矢量服务对应一个 FeatureType。 */
    public static String capabilities(String serviceUrl,
            List<PublicationLayer.VectorLayer> layers) {
        String baseUrl = stripTrailingSlash(serviceUrl);
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("wfs", "WFS_Capabilities", NAMESPACE);
            writer.writeNamespace("wfs", NAMESPACE);
            writer.writeNamespace("ows", OWS_NAMESPACE);
            writer.writeNamespace("xlink", XLINK_NAMESPACE);
            writer.writeNamespace("xsi", XSI_NAMESPACE);
            writer.writeAttribute("xsi", XSI_NAMESPACE, "schemaLocation",
                    NAMESPACE + " http://schemas.opengis.net/wfs/2.0/wfs.xsd");
            writer.writeAttribute("version", "2.0.0");

            writeServiceIdentification(writer);
            writeServiceProvider(writer);
            writeOperationsMetadata(writer, baseUrl);
            writeFeatureTypeList(writer, layers);

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 WFS 能力文档失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    /**
     * 生成 DescribeFeatureType 的 XSD。
     *
     * <p>原始属性由 JSONB 动态字段组成，无法在发布时穷举，因此只声明系统固定投影
     * 字段与几何，其余属性通过 {@code xs:any} 放行；客户端以 GeoJSON 取要素为准。</p>
     */
    public static String describeFeatureType(
            List<PublicationLayer.VectorLayer> layers) {
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("xs", "schema", XS_NAMESPACE);
            writer.writeNamespace("xs", XS_NAMESPACE);
            writer.writeAttribute("elementFormDefault", "qualified");
            writer.writeAttribute("version", "1.0");

            writer.writeStartElement("xs", "complexType", XS_NAMESPACE);
            writer.writeAttribute("name", "VectorFeatureType");
            writer.writeStartElement("xs", "sequence", XS_NAMESPACE);
            writeXsElement(writer, "geometry", "xs:anyType");
            for (String field : List.of("fclass", "code", "id", "name", "ref")) {
                writeXsElement(writer, field, "xs:string");
            }
            writer.writeStartElement("xs", "any", XS_NAMESPACE);
            writer.writeAttribute("namespace", "##any");
            writer.writeAttribute("processContents", "lax");
            writer.writeAttribute("minOccurs", "0");
            writer.writeAttribute("maxOccurs", "unbounded");
            writer.writeEndElement();
            writer.writeEndElement(); // sequence
            writer.writeEndElement(); // complexType

            for (PublicationLayer.VectorLayer layer : layers) {
                writer.writeStartElement("xs", "element", XS_NAMESPACE);
                writer.writeAttribute("name", layer.serviceCode());
                writer.writeAttribute("type", "VectorFeatureType");
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 WFS 要素类型描述失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    /** OWS 1.1 异常报告；WFS 2.0 与 WMTS 共用同一通用结构，直接复用现有输出。 */
    public static String exceptionReport(String code, String locator, String message) {
        return WmtsXmlWriter.exceptionReport(code, locator, message);
    }

    /** 未启用任何服务器端存储过程，按 WFS 2.0 返回空响应文档。 */
    public static String emptyStoredQueries(String operation) {
        String root = "ListStoredQueries".equalsIgnoreCase(operation)
                ? "ListStoredQueriesResponse" : "DescribeStoredQueriesResponse";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<wfs:" + root + " xmlns:wfs=\"" + NAMESPACE + "\"/>";
    }

    private static void writeServiceIdentification(XMLStreamWriter writer)
            throws XMLStreamException {
        writer.writeStartElement("ows", "ServiceIdentification", OWS_NAMESPACE);
        writeOwsText(writer, "Title", "Ocean Admin Web Feature Service");
        writeOwsText(writer, "Abstract", "Ocean Admin 已发布矢量服务的只读要素服务 (WFS 2.0.0)");
        writeOwsText(writer, "ServiceType", "WFS");
        writeOwsText(writer, "ServiceTypeVersion", "2.0.0");
        writeOwsText(writer, "Fees", "NONE");
        writeOwsText(writer, "AccessConstraints", "NONE");
        writer.writeEndElement();
    }

    private static void writeServiceProvider(XMLStreamWriter writer)
            throws XMLStreamException {
        writer.writeStartElement("ows", "ServiceProvider", OWS_NAMESPACE);
        writeOwsText(writer, "ProviderName", "Ocean Admin");
        writer.writeStartElement("ows", "ServiceContact", OWS_NAMESPACE);
        writeOwsText(writer, "IndividualName", "Ocean Admin");
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeOperationsMetadata(XMLStreamWriter writer, String baseUrl)
            throws XMLStreamException {
        writer.writeStartElement("ows", "OperationsMetadata", OWS_NAMESPACE);
        writeOperation(writer, "GetCapabilities", baseUrl, false);
        writeOperation(writer, "DescribeFeatureType", baseUrl, false);
        writeOperation(writer, "GetFeature", baseUrl, true);
        writeOperation(writer, "GetPropertyValue", baseUrl, false);
        writeOperation(writer, "ListStoredQueries", baseUrl, false);
        writeOperation(writer, "DescribeStoredQueries", baseUrl, false);
        writer.writeEndElement();
    }

    private static void writeOperation(XMLStreamWriter writer, String name,
            String baseUrl, boolean outputFormats) throws XMLStreamException {
        writer.writeStartElement("ows", "Operation", OWS_NAMESPACE);
        writer.writeAttribute("name", name);
        writer.writeStartElement("ows", "DCP", OWS_NAMESPACE);
        writer.writeStartElement("ows", "HTTP", OWS_NAMESPACE);
        writer.writeStartElement("ows", "Get", OWS_NAMESPACE);
        writer.writeAttribute("xlink", XLINK_NAMESPACE, "href",
                baseUrl + "?SERVICE=WFS&REQUEST=" + name);
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        if (outputFormats) {
            writer.writeStartElement("ows", "Parameter", OWS_NAMESPACE);
            writer.writeAttribute("name", "outputFormat");
            writer.writeStartElement("ows", "AllowedValues", OWS_NAMESPACE);
            writeOwsText(writer, "Value", WfsService.GEOJSON);
            writeOwsText(writer, "Value", WfsService.JSON);
            writeOwsText(writer, "Value", WfsService.TEXT_JSON);
            writer.writeEndElement();
            writer.writeEndElement();
        }
        writer.writeEndElement();
    }

    private static void writeFeatureTypeList(XMLStreamWriter writer,
            List<PublicationLayer.VectorLayer> layers)
            throws XMLStreamException {
        writer.writeStartElement("wfs", "FeatureTypeList", NAMESPACE);
        for (PublicationLayer.VectorLayer layer : layers) {
            writer.writeStartElement("wfs", "FeatureType", NAMESPACE);
            writeWfsText(writer, "Name", layer.serviceCode());
            writeWfsText(writer, "Title", displayTitle(layer.title(), layer.layerName(), layer.serviceCode()));
            writeWfsText(writer, "Abstract",
                    displayAbstract(layer.abstractText(), layer.keywords(), layer.serviceCode()));
            writeWfsText(writer, "DefaultCRS", DEFAULT_CRS);
            writeWfsText(writer, "OtherCRS", MERCATOR_CRS);
            double[] bounds = layer.wgs84Bounds();
            writer.writeStartElement("ows", "WGS84BoundingBox", OWS_NAMESPACE);
            writeOwsText(writer, "LowerCorner", bounds[0] + " " + bounds[1]);
            writeOwsText(writer, "UpperCorner", bounds[2] + " " + bounds[3]);
            writer.writeEndElement();
            writer.writeEndElement(); // FeatureType
        }
        writer.writeEndElement();
    }

    private static void writeXsElement(XMLStreamWriter writer, String name, String type)
            throws XMLStreamException {
        writer.writeStartElement("xs", "element", XS_NAMESPACE);
        writer.writeAttribute("name", name);
        writer.writeAttribute("type", type);
        writer.writeAttribute("minOccurs", "0");
        writer.writeEndElement();
    }

    private static void writeOwsText(XMLStreamWriter writer, String localName, String value)
            throws XMLStreamException {
        writeText(writer, "ows", OWS_NAMESPACE, localName, value);
    }

    private static void writeWfsText(XMLStreamWriter writer, String localName, String value)
            throws XMLStreamException {
        writeText(writer, "wfs", NAMESPACE, localName, value);
    }

    private static void writeText(XMLStreamWriter writer, String prefix, String namespace,
            String localName, String value) throws XMLStreamException {
        writer.writeStartElement(prefix, localName, namespace);
        writer.writeCharacters(value == null ? "" : value);
        writer.writeEndElement();
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
                // 关闭失败不影响已生成的文档内容
            }
        }
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
