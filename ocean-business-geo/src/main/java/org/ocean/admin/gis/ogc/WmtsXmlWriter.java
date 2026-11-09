package org.ocean.admin.gis.ogc;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WMTS 1.0.0 能力文档与 OGC 异常报告的 XML 输出。
 *
 * <p>元素命名空间与出现顺序遵循 OGC 08-068r2 的 wmtsGetCapabilities_response.xsd：
 * 图层与矩阵集元素属于 WMTS 命名空间，通用描述元素属于 OWS 命名空间。</p>
 */
public final class WmtsXmlWriter {

    static final String NAMESPACE = "http://www.opengis.net/wmts/1.0";
    static final String OWS_NAMESPACE = "http://www.opengis.net/ows/1.1";
    static final String XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";
    static final String XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String DEFAULT_CRS = "urn:ogc:def:crs:OGC:1.3:CRS84";

    private WmtsXmlWriter() {
    }

    /**
     * 生成 GetCapabilities 响应。
     *
     * @param serviceUrl 服务根地址，用于 OnlineResource 与资源模板
     * @param layers     已启用图层
     */
    public static String capabilities(String serviceUrl,
            List<PublishedImageryCatalog.PublishedLayer> layers,
            List<PublishedVectorCatalog.VectorLayerView> vectorLayers) {
        String baseUrl = stripTrailingSlash(serviceUrl);
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("", "Capabilities", NAMESPACE);
            writer.writeDefaultNamespace(NAMESPACE);
            writer.writeNamespace("ows", OWS_NAMESPACE);
            writer.writeNamespace("xlink", XLINK_NAMESPACE);
            writer.writeNamespace("xsi", XSI_NAMESPACE);
            writer.writeAttribute("xsi", XSI_NAMESPACE, "schemaLocation",
                    NAMESPACE + " http://schemas.opengis.net/wmts/1.0/wmtsGetCapabilities_response.xsd");
            writer.writeAttribute("version", "1.0.0");

            writeServiceIdentification(writer);
            writeServiceProvider(writer);
            writeOperationsMetadata(writer, baseUrl);
            writeContents(writer, baseUrl, layers, vectorLayers);

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 WMTS 能力文档失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    /**
     * 生成 OGC ExceptionReport。
     *
     * @param code    异常代码，如 InvalidParameterValue、TileOutOfRange
     * @param locator 出错参数名，可为空
     * @param message 可读说明
     */
    public static String exceptionReport(String code, String locator, String message) {
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("ows", "ExceptionReport", OWS_NAMESPACE);
            writer.writeNamespace("ows", OWS_NAMESPACE);
            writer.writeAttribute("version", "1.1.0");
            writer.writeStartElement("ows", "Exception", OWS_NAMESPACE);
            writer.writeAttribute("exceptionCode", code == null ? "NoApplicableCode" : code);
            if (locator != null && !locator.isBlank()) {
                writer.writeAttribute("locator", locator);
            }
            writer.writeStartElement("ows", "ExceptionText", OWS_NAMESPACE);
            writer.writeCharacters(message == null ? "" : message);
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 OGC 异常报告失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    private static void writeServiceIdentification(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("ows", "ServiceIdentification", OWS_NAMESPACE);
        writeOwsText(writer, "Title", "Ocean Admin Web Map Tile Service");
        writeOwsText(writer, "Abstract", "Ocean Admin 已发布栅格瓦片服务");
        writeOwsText(writer, "ServiceType", "OGC WMTS");
        writeOwsText(writer, "ServiceTypeVersion", "1.0.0");
        writeOwsText(writer, "Fees", "NONE");
        writeOwsText(writer, "AccessConstraints", "NONE");
        writer.writeEndElement();
    }

    private static void writeServiceProvider(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("ows", "ServiceProvider", OWS_NAMESPACE);
        writeOwsText(writer, "ProviderName", "Ocean Admin");
        writer.writeStartElement("ows", "ProviderSite", OWS_NAMESPACE);
        writer.writeAttribute("xlink", XLINK_NAMESPACE, "href", "");
        writer.writeEndElement();
        writer.writeStartElement("ows", "ServiceContact", OWS_NAMESPACE);
        writeOwsText(writer, "IndividualName", "Ocean Admin");
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeOperationsMetadata(XMLStreamWriter writer, String baseUrl)
            throws XMLStreamException {
        writer.writeStartElement("ows", "OperationsMetadata", OWS_NAMESPACE);
        writeOperation(writer, "GetCapabilities", baseUrl + "?SERVICE=WMTS&REQUEST=GetCapabilities");
        writeOperation(writer, "GetTile", baseUrl + "?SERVICE=WMTS&REQUEST=GetTile");
        writer.writeEndElement();
    }

    private static void writeOperation(XMLStreamWriter writer, String name, String href)
            throws XMLStreamException {
        writer.writeStartElement("ows", "Operation", OWS_NAMESPACE);
        writer.writeAttribute("name", name);
        writer.writeStartElement("ows", "DCP", OWS_NAMESPACE);
        writer.writeStartElement("ows", "HTTP", OWS_NAMESPACE);
        writer.writeStartElement("ows", "Get", OWS_NAMESPACE);
        writer.writeAttribute("xlink", XLINK_NAMESPACE, "href", href);
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeContents(XMLStreamWriter writer, String baseUrl,
            List<PublishedImageryCatalog.PublishedLayer> layers,
            List<PublishedVectorCatalog.VectorLayerView> vectorLayers)
            throws XMLStreamException {
        writer.writeStartElement("", "Contents", NAMESPACE);
        for (PublishedImageryCatalog.PublishedLayer layer : layers) {
            writeLayer(writer, baseUrl, layer);
        }
        writeVectorLayers(writer, baseUrl, vectorLayers);
        for (TileMatrixSetDefinition definition : usedMatrixSets(layers, vectorLayers)) {
            writeMatrixSet(writer, definition);
        }
        writer.writeEndElement();
    }

    /** 动态矢量图层同时提供两种矩阵集，能力文档声明其引用过的矩阵集。 */
    private static List<TileMatrixSetDefinition> usedMatrixSets(
            List<PublishedImageryCatalog.PublishedLayer> layers,
            List<PublishedVectorCatalog.VectorLayerView> vectorLayers) {
        if (layers.isEmpty() && vectorLayers.isEmpty()) {
            return List.of(TileMatrixSetDefinition.values());
        }
        List<TileMatrixSetDefinition> used = new ArrayList<>();
        for (PublishedImageryCatalog.PublishedLayer layer : layers) {
            if (!used.contains(layer.matrixSet())) {
                used.add(layer.matrixSet());
            }
        }
        for (PublishedVectorCatalog.VectorLayerView layer : vectorLayers) {
            if (!used.contains(layer.matrixSet())) {
                used.add(layer.matrixSet());
            }
        }
        return used;
    }

    /**
     * 图层元素顺序遵循 LayerType：ows:Title、ows:Identifier、ows:WGS84BoundingBox、
     * Style、Format、TileMatrixSetLink、ResourceURL。
     */
    private static void writeLayer(XMLStreamWriter writer, String baseUrl,
            PublishedImageryCatalog.PublishedLayer layer) throws XMLStreamException {
        writer.writeStartElement("", "Layer", NAMESPACE);
        writeOwsText(writer, "Title", catalogTitle(layer));
        writeOwsText(writer, "Identifier", layer.serviceCode());
        writeBoundingBox(writer, layer);

        writer.writeStartElement("", "Style", NAMESPACE);
        writer.writeAttribute("isDefault", "true");
        writeOwsText(writer, "Identifier", "default");
        writer.writeEndElement();

        writer.writeStartElement("", "Format", NAMESPACE);
        writer.writeCharacters(layer.mediaType());
        writer.writeEndElement();

        writeTileMatrixSetLink(writer, layer);

        String template = baseUrl + "/rest/" + layer.serviceCode()
                + "/default/" + layer.matrixSet().id()
                + "/{TileMatrix}/{TileRow}/{TileCol}." + layer.extension();
        writer.writeStartElement("", "ResourceURL", NAMESPACE);
        writer.writeAttribute("format", layer.mediaType());
        writer.writeAttribute("resourceType", "tile");
        writer.writeAttribute("template", template);
        writer.writeEndElement();
        writer.writeEndElement();
    }

    /** 同一矢量服务按服务编码只输出一个 Layer，两种矩阵集都挂到 TileMatrixSetLink。 */
    private static void writeVectorLayers(XMLStreamWriter writer, String baseUrl,
            List<PublishedVectorCatalog.VectorLayerView> vectorLayers)
            throws XMLStreamException {
        Map<String, List<PublishedVectorCatalog.VectorLayerView>> grouped = new LinkedHashMap<>();
        for (PublishedVectorCatalog.VectorLayerView view : vectorLayers) {
            grouped.computeIfAbsent(view.serviceCode(), key -> new ArrayList<>()).add(view);
        }
        for (Map.Entry<String, List<PublishedVectorCatalog.VectorLayerView>> entry : grouped.entrySet()) {
            writeVectorLayer(writer, baseUrl, entry.getKey(), entry.getValue());
        }
    }

    /** 矢量图层标识使用服务编码，矩阵集由 TileMatrixSetLink 声明。 */
    private static void writeVectorLayer(XMLStreamWriter writer, String baseUrl,
            String identifier, List<PublishedVectorCatalog.VectorLayerView> views)
            throws XMLStreamException {
        PublishedVectorCatalog.VectorLayerView first = views.getFirst();
        writer.writeStartElement("", "Layer", NAMESPACE);
        writeOwsText(writer, "Title", first.title());
        writeOwsText(writer, "Identifier", identifier);
        writeOwsText(writer, "Abstract", "已发布矢量服务的动态栅格瓦片");
        writer.writeStartElement("ows", "WGS84BoundingBox", OWS_NAMESPACE);
        writeOwsText(writer, "LowerCorner",
                first.wgs84Bounds()[0] + " " + first.wgs84Bounds()[1]);
        writeOwsText(writer, "UpperCorner",
                first.wgs84Bounds()[2] + " " + first.wgs84Bounds()[3]);
        writer.writeEndElement();
        for (PublishedVectorCatalog.StyleOption style : first.styles()) {
            writer.writeStartElement("", "Style", NAMESPACE);
            writer.writeAttribute("isDefault",
                    Boolean.toString(style.id().equals(first.defaultStyleId())));
            writeOwsText(writer, "Identifier", style.id());
            writeOwsText(writer, "Title", style.name());
            writer.writeEndElement();
        }
        writer.writeStartElement("", "Format", NAMESPACE);
        writer.writeCharacters(first.mediaType());
        writer.writeEndElement();
        for (PublishedVectorCatalog.VectorLayerView view : views) {
            writeVectorMatrixSetLink(writer, view);
            writer.writeStartElement("", "ResourceURL", NAMESPACE);
            writer.writeAttribute("format", view.mediaType());
            writer.writeAttribute("resourceType", "tile");
            writer.writeAttribute("template", baseUrl + "/rest/" + identifier
                    + "/{Style}/" + view.matrixSet().id()
                    + "/{TileMatrix}/{TileRow}/{TileCol}." + view.extension());
            writer.writeEndElement();
        }
        writer.writeEndElement();
    }

    private static void writeVectorMatrixSetLink(XMLStreamWriter writer,
            PublishedVectorCatalog.VectorLayerView layer) throws XMLStreamException {
        writer.writeStartElement("", "TileMatrixSetLink", NAMESPACE);
        writeText(writer, "", NAMESPACE, "TileMatrixSet", layer.matrixSet().id());
        writer.writeStartElement("", "TileMatrixSetLimits", NAMESPACE);
        for (int zoom = layer.minZoom(); zoom <= layer.maxZoom(); zoom++) {
            if (layer.matrixSet().matrixWidth(zoom) < 2
                    || layer.matrixSet().matrixHeight(zoom) < 2) {
                continue;
            }
            TileMatrixSetDefinition.MatrixLimits limits =
                    layer.matrixSet().limits(zoom, layer.wgs84Bounds());
            writer.writeStartElement("", "TileMatrixLimits", NAMESPACE);
            writeText(writer, "", NAMESPACE, "TileMatrix", Integer.toString(zoom));
            writeText(writer, "", NAMESPACE, "MinTileRow", Integer.toString(limits.minRow()));
            writeText(writer, "", NAMESPACE, "MaxTileRow", Integer.toString(limits.maxRow()));
            writeText(writer, "", NAMESPACE, "MinTileCol", Integer.toString(limits.minCol()));
            writeText(writer, "", NAMESPACE, "MaxTileCol", Integer.toString(limits.maxCol()));
            writer.writeEndElement();
        }
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeBoundingBox(XMLStreamWriter writer,
            PublishedImageryCatalog.PublishedLayer layer) throws XMLStreamException {
        double[] bounds = layer.wgs84Bounds();
        writer.writeStartElement("ows", "WGS84BoundingBox", OWS_NAMESPACE);
        writeOwsText(writer, "LowerCorner", bounds[0] + " " + bounds[1]);
        writeOwsText(writer, "UpperCorner", bounds[2] + " " + bounds[3]);
        writer.writeEndElement();
    }

    private static void writeTileMatrixSetLink(XMLStreamWriter writer,
            PublishedImageryCatalog.PublishedLayer layer) throws XMLStreamException {
        writer.writeStartElement("", "TileMatrixSetLink", NAMESPACE);
        writer.writeStartElement("", "TileMatrixSet", NAMESPACE);
        writer.writeCharacters(layer.matrixSet().id());
        writer.writeEndElement();
        if (layer.minZoom() <= layer.maxZoom()) {
            writer.writeStartElement("", "TileMatrixSetLimits", NAMESPACE);
            for (int zoom = layer.minZoom(); zoom <= layer.maxZoom(); zoom++) {
                // 单瓦片矩阵（1x1 或 1 行）没有可限定的行列范围，且 MaxTileRow/MaxTileCol
                // 在 OGC 结构中要求为正整数，此处跳过以免能力文档校验失败。
                if (layer.matrixSet().matrixWidth(zoom) < 2
                        || layer.matrixSet().matrixHeight(zoom) < 2) {
                    continue;
                }
                writeMatrixLimits(writer, layer, zoom);
            }
            writer.writeEndElement();
        }
        writer.writeEndElement();
    }

    /** 按图层地理范围收窄矩阵集，提示客户端只请求实际有数据的瓦片。 */
    private static void writeMatrixLimits(XMLStreamWriter writer,
            PublishedImageryCatalog.PublishedLayer layer, int zoom) throws XMLStreamException {
        TileMatrixSetDefinition.MatrixLimits limits =
                layer.matrixSet().limits(zoom, layer.wgs84Bounds());
        writer.writeStartElement("", "TileMatrixLimits", NAMESPACE);
        writeText(writer, "", NAMESPACE, "TileMatrix", Integer.toString(zoom));
        writeText(writer, "", NAMESPACE, "MinTileRow", Integer.toString(limits.minRow()));
        writeText(writer, "", NAMESPACE, "MaxTileRow", Integer.toString(limits.maxRow()));
        writeText(writer, "", NAMESPACE, "MinTileCol", Integer.toString(limits.minCol()));
        writeText(writer, "", NAMESPACE, "MaxTileCol", Integer.toString(limits.maxCol()));
        writer.writeEndElement();
    }

    /** 矩阵集元素顺序遵循 TileMatrixSetType：ows:Identifier、ows:SupportedCRS、WellKnownScaleSet、TileMatrix。 */
    private static void writeMatrixSet(XMLStreamWriter writer, TileMatrixSetDefinition definition)
            throws XMLStreamException {
        writer.writeStartElement("", "TileMatrixSet", NAMESPACE);
        writeOwsText(writer, "Identifier", definition.id());
        writeOwsText(writer, "SupportedCRS",
                definition.supportedCrs() == null ? DEFAULT_CRS : definition.supportedCrs());
        writer.writeStartElement("", "WellKnownScaleSet", NAMESPACE);
        writer.writeCharacters(definition.wellKnownScaleSet());
        writer.writeEndElement();
        for (int zoom = 0; zoom <= TileMatrixSetDefinition.MAX_ZOOM; zoom++) {
            writer.writeStartElement("", "TileMatrix", NAMESPACE);
            writeOwsText(writer, "Identifier", Integer.toString(zoom));
            writeText(writer, "", NAMESPACE, "ScaleDenominator",
                    Double.toString(definition.scaleDenominator(zoom)));
            writeText(writer, "", NAMESPACE, "TopLeftCorner",
                    definition.topLeftX() + " " + definition.topLeftY());
            writeText(writer, "", NAMESPACE, "TileWidth",
                    Integer.toString(TileMatrixSetDefinition.TILE_SIZE));
            writeText(writer, "", NAMESPACE, "TileHeight",
                    Integer.toString(TileMatrixSetDefinition.TILE_SIZE));
            writeText(writer, "", NAMESPACE, "MatrixWidth",
                    Integer.toString(definition.matrixWidth(zoom)));
            writeText(writer, "", NAMESPACE, "MatrixHeight",
                    Integer.toString(definition.matrixHeight(zoom)));
            writer.writeEndElement();
        }
        writer.writeEndElement();
    }

    private static void writeOwsText(XMLStreamWriter writer, String localName, String value)
            throws XMLStreamException {
        writeText(writer, "ows", OWS_NAMESPACE, localName, value);
    }

    private static void writeText(XMLStreamWriter writer, String prefix, String namespace,
            String localName, String value) throws XMLStreamException {
        writer.writeStartElement(prefix, localName, namespace);
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
                // 关闭失败不影响已生成的文档内容。
            }
        }
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
