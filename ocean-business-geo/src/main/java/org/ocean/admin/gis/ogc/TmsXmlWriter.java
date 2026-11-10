package org.ocean.admin.gis.ogc;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;

/**
 * TMS 1.0.0 文档输出（OSGeo Tile Map Service Specification 1.0.0）。
 *
 * <p>与 GeoServer 内置 TMS 服务保持一致：文档不带命名空间，只有
 * {@code TileMapService} 与 {@code TileMap} 两种元素。规范本身没有定义异常报文，
 * 出错时由控制层直接返回 HTTP 状态码，因此这里不产出错误文档。</p>
 */
public final class TmsXmlWriter {

    private static final String VERSION = "1.0.0";

    private TmsXmlWriter() {
    }

    /**
     * 生成 TileMapService 根文档，列出全部已发布图层。
     *
     * @param baseUrl 服务根地址，如 {@code http://host:8090/gis/tms/1.0.0}
     * @param layers  已发布图层
     */
    public static String tileMapService(String baseUrl,
            List<PublicationLayer.RasterLayer> layers,
            List<PublicationLayer.VectorLayerView> vectorLayers) {
        String root = stripTrailingSlash(baseUrl);
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("TileMapService");
            writer.writeAttribute("version", VERSION);
            writer.writeAttribute("services", root);
            writeText(writer, "Title", "Ocean Admin Tile Map Service");
            writeText(writer, "Abstract", "Ocean Admin 已发布栅格瓦片服务 (TMS 1.0.0)");

            writer.writeStartElement("TileMaps");
            for (PublicationLayer.RasterLayer layer : layers) {
                writer.writeStartElement("TileMap");
                writer.writeAttribute("title", layer.serviceCode());
                writer.writeAttribute("srs", layer.matrixSet().tmsSrs());
                writer.writeAttribute("profile", layer.matrixSet().tmsProfile());
                writer.writeAttribute("href", tileMapHref(root, layer));
                writer.writeEndElement(); // TileMap
            }
            for (PublicationLayer.VectorLayerView layer : vectorLayers) {
                writer.writeStartElement("TileMap");
                writer.writeAttribute("title", vectorSpec(layer));
                writer.writeAttribute("srs", layer.matrixSet().tmsSrs());
                writer.writeAttribute("profile", layer.matrixSet().tmsProfile());
                writer.writeAttribute("href", root + "/" + vectorSpec(layer));
                writer.writeEndElement(); // TileMap
            }
            writer.writeEndElement(); // TileMaps

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 TMS 服务文档失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    /**
     * 生成单个 TileMap 文档。
     *
     * <p>{@code Origin} 取矩阵集左下角而非图层范围，{@code BoundingBox} 取图层实际范围，
     * 与 GeoWebCache 的输出一致——客户端据此把瓦片行列号对齐到全局矩阵。</p>
     *
     * @param baseUrl 服务根地址，如 {@code http://host:8090/gis/tms/1.0.0}
     * @param layer   已发布图层
     */
    public static String tileMap(String baseUrl, PublicationLayer.RasterLayer layer) {
        return tileMap(baseUrl, layer.serviceCode(), catalogTitle(layer),
                layer.matrixSet(), layer.wgs84Bounds(), layer.mediaType(),
                layer.extension(), layer.minZoom(), layer.maxZoom(),
                spec(layer));
    }

    /** 矢量 TileMap 文档；参数与影像一致，取图地址由动态渲染器消费。 */
    public static String tileMap(String baseUrl,
            PublicationLayer.VectorLayerView layer) {
        return tileMap(baseUrl, vectorSpec(layer), layer.title(),
                layer.matrixSet(), layer.wgs84Bounds(), layer.mediaType(),
                layer.extension(), layer.minZoom(), layer.maxZoom(),
                vectorSpec(layer));
    }

    private static String tileMap(String baseUrl, String identifier, String title,
            TileMatrixSetDefinition matrixSet, double[] wgs84Bounds,
            String mediaType, String extension, int minZoom, int maxZoom, String hrefSuffix) {
        String root = stripTrailingSlash(baseUrl);
        double[] bounds = matrixSet.targetBounds(wgs84Bounds);
        StringWriter buffer = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = newWriter(buffer);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("TileMap");
            writer.writeAttribute("version", VERSION);
            writer.writeAttribute("tilemapservice", root);

            writeText(writer, "Title", title);
            writeText(writer, "Abstract", "已发布 OGC 服务: " + identifier);
            writeText(writer, "SRS", matrixSet.tmsSrs());

            writer.writeStartElement("BoundingBox");
            writer.writeAttribute("minx", decimal(bounds[0]));
            writer.writeAttribute("miny", decimal(bounds[1]));
            writer.writeAttribute("maxx", decimal(bounds[2]));
            writer.writeAttribute("maxy", decimal(bounds[3]));
            writer.writeEndElement();

            writer.writeStartElement("Origin");
            writer.writeAttribute("x", decimal(matrixSet.originX()));
            writer.writeAttribute("y", decimal(matrixSet.originY()));
            writer.writeEndElement();

            writer.writeStartElement("TileFormat");
            writer.writeAttribute("width", Integer.toString(TileMatrixSetDefinition.TILE_SIZE));
            writer.writeAttribute("height", Integer.toString(TileMatrixSetDefinition.TILE_SIZE));
            writer.writeAttribute("mime-type", mediaType);
            writer.writeAttribute("extension", extension);
            writer.writeEndElement();

            String href = root + "/" + hrefSuffix;
            writer.writeStartElement("TileSets");
            writer.writeAttribute("profile", matrixSet.tmsProfile());
            for (int zoom = minZoom; zoom <= maxZoom; zoom++) {
                writer.writeStartElement("TileSet");
                writer.writeAttribute("href", href + "/" + zoom);
                writer.writeAttribute("units-per-pixel", decimal(matrixSet.unitsPerPixel(zoom)));
                writer.writeAttribute("order", Integer.toString(zoom));
                writer.writeEndElement();
            }
            writer.writeEndElement(); // TileSets

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
            return buffer.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成 TMS 图层文档失败", ex);
        } finally {
            closeQuietly(writer);
        }
    }

    /**
     * TileMap 在地址中的标识：{@code 服务编码@矩阵集@格式}，与 GeoServer 的写法一致。
     */
    static String spec(PublicationLayer.RasterLayer layer) {
        return layer.serviceCode() + "@" + layer.matrixSet().id() + "@" + layer.extension();
    }

    /** 矢量 TileMap 标识：{@code 服务编码@矩阵集@格式@vector}。 */
    static String vectorSpec(PublicationLayer.VectorLayerView layer) {
        return layer.serviceCode() + "@" + layer.matrixSet().id()
                + "@" + layer.extension() + "@vector";
    }

    private static String tileMapHref(String root, PublicationLayer.RasterLayer layer) {
        return root + "/" + spec(layer);
    }

    /**
     * 坐标与分辨率一律输出普通十进制。
     *
     * <p>{@code Double.toString} 对墨卡托原点会给出 {@code -2.0037508342789244E7} 这样的科学计数法，
     * 客户端按定点小数解析时会取到错误数值，因此这里统一转成 plain string。</p>
     */
    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void writeText(XMLStreamWriter writer, String localName, String value)
            throws XMLStreamException {
        writer.writeStartElement(localName);
        writer.writeCharacters(value == null ? "" : value);
        writer.writeEndElement();
    }

    /** 能力文档展示名优先使用发布时填写的图层名称，缺失时回退服务编码。 */
    private static String catalogTitle(PublicationLayer.RasterLayer layer) {
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
                // 关闭失败不影响已生成的文档内容
            }
        }
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
