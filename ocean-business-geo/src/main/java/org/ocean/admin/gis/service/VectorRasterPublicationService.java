package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 将已完成的矢量配图 PNG 作为 TMS 和 WMTS 发布，两种协议共用原成果文件。 */
@Service
@RequiredArgsConstructor
public class VectorRasterPublicationService implements PublicationProtocol {
    private static final double HALF_WORLD = Math.PI * 6378137;
    private static final String MATRIX_SET = "WebMercatorQuad";
    private static final String WMTS_NS = "http://www.opengis.net/wmts/1.0";
    private static final String OWS_NS = "http://www.opengis.net/ows/1.1";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final Set<String> SECTIONS = Set.of("ServiceIdentification", "ServiceProvider",
            "OperationsMetadata", "Contents", "Themes");

    private final PublicationRecordService records;
    private final GisProcessingStorageService storage;
    private final ObjectMapper json;
    private final Map<String, Tiles> metadata = new ConcurrentHashMap<>();

    @Override public GisProcessingType type() { return GisProcessingType.VECTOR; }
    @Override public String displayName() { return "静态栅格瓦片服务"; }
    @Override public String generateServiceCode() {
        return "VRT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }
    @Override public String normalizeServiceCode(String code) {
        if (code == null || !code.matches("VRT_[a-fA-F0-9]{20}"))
            throw new IllegalArgumentException("非法静态栅格服务编码");
        return code.toUpperCase(Locale.ROOT);
    }

    @Override public PublishedArtifact validateForPublish(GisProcessingTask task, GisTileSet tileSet) {
        if (task.getProcessingType() != type() || task.getTaskStatus() != TaskStatus.COMPLETED
                || !task.getId().equals(tileSet.getTaskId()))
            throw new IllegalStateException("只能发布已完成的矢量静态栅格任务");
        Tiles tiles = readTiles(tileSet);
        return new PublishedArtifact(tiles.root(), "png", tiles.minZoom(), tiles.maxZoom(), "EPSG:3857");
    }

    // 发布状态以数据库为准，元数据只在首个请求时装载，事务回滚不会留下可访问的服务。
    @Override public void activate(String code, PublishedArtifact artifact) { metadata.remove(code); }
    @Override public void deactivate(String code) { metadata.remove(code); }

    @Override public PublicationAccess buildAccess(GisPublication publication, GisTileSet tileSet) {
        String root = records.buildServiceRoot(type(), publication.getServiceCode());
        return new PublicationAccess(root + "wmts", root + "wmts?SERVICE=WMTS&REQUEST=GetCapabilities",
                root + "wmts/1.0.0/default/" + MATRIX_SET + "/{TileMatrix}/{TileRow}/{TileCol}.png",
                root + "tms/1.0.0/tilemap.xml", root + "tms/1.0.0/{z}/{x}/{y}.png");
    }

    /** 通用文件入口只允许受控的 TMS 瓦片路径，描述文档由专用协议入口生成。 */
    @Override public Path resolvePublishedFile(String code, String relativePath) {
        String path = relativePath == null ? "" : relativePath.replaceFirst("^/", "");
        if (!path.matches("tms/1\\.0\\.0/\\d{1,2}/\\d+/\\d+\\.png"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "静态瓦片资源不存在");
        String[] parts = path.split("/");
        return tmsTile(code, parts[2], parts[3], parts[4].replace(".png", ""));
    }

    private Published source(String code) {
        GisPublication publication;
        try {
            publication = records.getRequired(type(), normalizeServiceCode(code));
        } catch (IllegalArgumentException ex) {
            throw new WmtsException("InvalidParameterValue", "LAYER", "静态瓦片服务不存在", HttpStatus.NOT_FOUND);
        }
        if (!PublicationRecordService.PUBLISHED.equals(publication.getStatus()))
            throw new WmtsException("InvalidParameterValue", "LAYER", "静态瓦片服务已停用", HttpStatus.NOT_FOUND);
        try {
            Tiles tiles = metadata.computeIfAbsent(publication.getServiceCode(),
                    ignored -> readTiles(records.getPublicationTileSet(publication)));
            return new Published(publication, tiles);
        } catch (IllegalStateException ex) {
            throw new WmtsException("NoApplicableCode", "", "静态瓦片成果不可用", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Tiles readTiles(GisTileSet tileSet) {
        if (tileSet.getTileType() != type() || !"READY".equals(tileSet.getTileSetStatus())
                || !"PNG".equals(tileSet.getOutputFormat()) || !"XYZ".equals(tileSet.getTileProfile())
                || !"EPSG:3857".equals(tileSet.getTargetCrs()) || tileSet.getOutputKey() == null
                || !tileSet.getOutputKey().startsWith("vector/"))
            throw new IllegalStateException("发布成果必须是就绪的 EPSG:3857 / XYZ / PNG 瓦片集");
        try {
            Path root = storage.resolveTiles(tileSet.getOutputKey()).toRealPath();
            Path manifest = root.resolve("manifest.json").toRealPath();
            if (!manifest.startsWith(root)) throw new IllegalStateException("成果描述文件路径无效");
            var node = json.readTree(manifest.toFile());
            int min = node.path("minZoom").asInt(-1), max = node.path("maxZoom").asInt(-1);
            if (node.path("schemaVersion").asInt() != 1 || !"STATIC_RASTER_TILE_SET".equals(node.path("kind").asText())
                    || !"PNG".equals(node.path("format").asText()) || !"XYZ".equals(node.path("tileProfile").asText())
                    || !"EPSG:3857".equals(node.path("targetCrs").asText()) || node.path("tileSize").asInt() != 256
                    || min < 0 || max < min || max > 19 || !Integer.valueOf(min).equals(tileSet.getMinZoom())
                    || !Integer.valueOf(max).equals(tileSet.getMaxZoom()) || !node.path("bounds").isArray()
                    || node.path("bounds").size() != 4 || node.path("tileCount").asLong() <= 0)
                throw new IllegalStateException("静态栅格成果元数据与瓦片集不一致");
            double[] bounds = new double[4];
            for (int i = 0; i < 4; i++) {
                if (!node.path("bounds").get(i).isNumber()) throw new IllegalStateException("成果范围无效");
                bounds[i] = node.path("bounds").get(i).asDouble();
                if (!Double.isFinite(bounds[i])) throw new IllegalStateException("成果范围无效");
            }
            if (bounds[0] < -180 || bounds[2] > 180 || bounds[0] >= bounds[2]
                    || bounds[1] < -85.05112878 || bounds[3] > 85.05112878 || bounds[1] >= bounds[3])
                throw new IllegalStateException("成果范围无效");
            Tiles tiles = new Tiles(root, min, max, bounds);
            Range range = tiles.range(min);
            tile(tiles, min, range.minX(), range.minY());
            return tiles;
        } catch (IOException ex) {
            throw new IllegalStateException("静态栅格成果缺失或无法读取", ex);
        }
    }

    public Path tmsTile(String code, String zoom, String column, String row) {
        Tiles tiles = source(code).tiles();
        int z = index(zoom, "z"), x = index(column, "x"), y = index(row, "y");
        checkZoom(tiles, z);
        return tile(tiles, z, x, (1 << z) - 1 - y);
    }

    public Path wmtsTile(String code, String style, String matrixSet, String matrix, String row, String column) {
        Tiles tiles = source(code).tiles();
        if (!"default".equals(style)) throw invalid("STYLE");
        if (!MATRIX_SET.equals(matrixSet)) throw invalid("TILEMATRIXSET");
        return tile(tiles, index(matrix, "TILEMATRIX"), index(column, "TILECOL"), index(row, "TILEROW"));
    }

    /** KVP 参数名不区分大小写，但拒绝重复参数，避免不同代理对同一请求产生歧义。 */
    public WmtsResource wmts(String code, MultiValueMap<String, String> query) {
        Map<String, String> parameters = new HashMap<>();
        query.forEach((key, values) -> {
            String name = key.toUpperCase(Locale.ROOT);
            if (values.size() != 1 || parameters.putIfAbsent(name, values.getFirst()) != null) throw invalid(name);
        });
        if (!"WMTS".equals(required(parameters, "SERVICE"))) throw invalid("SERVICE");
        String request = required(parameters, "REQUEST");
        if ("GetCapabilities".equals(request)) {
            String versions = parameters.get("ACCEPTVERSIONS");
            if (versions != null && !Arrays.asList(versions.split(",")).contains("1.0.0"))
                throw new WmtsException("VersionNegotiationFailed", "ACCEPTVERSIONS", "仅支持 WMTS 1.0.0");
            String formats = parameters.get("ACCEPTFORMATS");
            if (formats != null && Arrays.stream(formats.split(",")).noneMatch("text/xml"::equals))
                throw invalid("ACCEPTFORMATS");
            Set<String> sections = parameters.containsKey("SECTIONS")
                    ? Set.copyOf(Arrays.asList(parameters.get("SECTIONS").split(","))) : SECTIONS;
            if (sections.contains("All")) sections = SECTIONS;
            if (!SECTIONS.containsAll(sections)) throw invalid("SECTIONS");
            String sequence = parameters.get("UPDATESEQUENCE");
            if (sequence != null) throw new WmtsException("InvalidUpdateSequence", "UPDATESEQUENCE", "服务不使用更新序号");
            return new WmtsResource(capabilities(code, sections), null);
        }
        if (!"GetTile".equals(request))
            throw new WmtsException("OperationNotSupported", "REQUEST", "不支持的 WMTS 操作");
        if (!"1.0.0".equals(required(parameters, "VERSION"))) throw invalid("VERSION");
        String normalizedCode;
        try { normalizedCode = normalizeServiceCode(code); }
        catch (IllegalArgumentException ex) { throw invalid("LAYER"); }
        if (!normalizedCode.equals(required(parameters, "LAYER"))) throw invalid("LAYER");
        if (!"image/png".equals(required(parameters, "FORMAT"))) throw invalid("FORMAT");
        return new WmtsResource(null, wmtsTile(code, required(parameters, "STYLE"), required(parameters, "TILEMATRIXSET"),
                required(parameters, "TILEMATRIX"), required(parameters, "TILEROW"), required(parameters, "TILECOL")));
    }

    private static String required(Map<String, String> parameters, String key) {
        String value = parameters.get(key);
        if (value == null || value.isEmpty())
            throw new WmtsException("MissingParameterValue", key, "缺少参数 " + key);
        return value;
    }

    private static int index(String value, String name) {
        if (value == null || !value.matches("\\d{1,9}")) throw invalid(name);
        return Integer.parseInt(value);
    }

    private static void checkZoom(Tiles tiles, int z) {
        if (z < tiles.minZoom() || z > tiles.maxZoom()) throw invalid("TILEMATRIX");
    }

    private static Path tile(Tiles tiles, int z, int x, int y) {
        checkZoom(tiles, z);
        Range range = tiles.range(z);
        if (x < range.minX() || x > range.maxX())
            throw new WmtsException("TileOutOfRange", "TILECOL", "列号超出成果范围");
        if (y < range.minY() || y > range.maxY())
            throw new WmtsException("TileOutOfRange", "TILEROW", "行号超出成果范围");
        try {
            Path file = tiles.root().resolve(z + "/" + x + "/" + y + ".png").toRealPath();
            if (!file.startsWith(tiles.root()) || !Files.isRegularFile(file))
                throw new WmtsException("NoApplicableCode", "", "瓦片不存在", HttpStatus.NOT_FOUND);
            return file;
        } catch (IOException ex) {
            throw new WmtsException("NoApplicableCode", "", "瓦片不存在", HttpStatus.NOT_FOUND);
        }
    }

    public String capabilities(String code) { return capabilities(code, SECTIONS); }

    private String capabilities(String code, Set<String> sections) {
        Published source = source(code);
        Tiles tiles = source.tiles();
        String id = source.publication().getServiceCode();
        String root = records.buildServiceRoot(type(), id);
        return xml(w -> {
            w.writeStartElement("Capabilities");
            w.writeDefaultNamespace(WMTS_NS);
            w.writeNamespace("ows", OWS_NS);
            w.writeNamespace("xlink", XLINK_NS);
            w.writeAttribute("version", "1.0.0");
            if (sections.contains("ServiceIdentification")) {
                w.writeStartElement("ows:ServiceIdentification");
                element(w, "ows:Title", source.publication().getServiceName());
                element(w, "ows:ServiceType", "OGC WMTS");
                element(w, "ows:ServiceTypeVersion", "1.0.0");
                w.writeEndElement();
            }
            if (sections.contains("ServiceProvider")) {
                w.writeStartElement("ows:ServiceProvider");
                element(w, "ows:ProviderName", "Ocean GIS");
                w.writeEmptyElement("ows:ServiceContact");
                w.writeEndElement();
            }
            if (sections.contains("OperationsMetadata")) {
                w.writeStartElement("ows:OperationsMetadata");
                for (String operation : new String[]{"GetCapabilities", "GetTile"}) {
                    w.writeStartElement("ows:Operation"); w.writeAttribute("name", operation);
                    w.writeStartElement("ows:DCP"); w.writeStartElement("ows:HTTP");
                    w.writeStartElement("ows:Get"); w.writeAttribute("xlink:href", root + "wmts?");
                    w.writeStartElement("ows:Constraint"); w.writeAttribute("name", "GetEncoding");
                    w.writeStartElement("ows:AllowedValues"); element(w, "ows:Value", "KVP");
                    for (int i = 0; i < 6; i++) w.writeEndElement();
                }
                w.writeEndElement();
            }
            if (sections.contains("Contents")) {
                w.writeStartElement("Contents"); w.writeStartElement("Layer");
                element(w, "ows:Title", source.publication().getServiceName());
                w.writeStartElement("ows:WGS84BoundingBox");
                element(w, "ows:LowerCorner", tiles.bounds()[0] + " " + tiles.bounds()[1]);
                element(w, "ows:UpperCorner", tiles.bounds()[2] + " " + tiles.bounds()[3]);
                w.writeEndElement();
                element(w, "ows:Identifier", id);
                w.writeStartElement("Style"); w.writeAttribute("isDefault", "true");
                element(w, "ows:Title", "切片时保存的配图样式"); element(w, "ows:Identifier", "default");
                w.writeEndElement(); element(w, "Format", "image/png");
                w.writeStartElement("TileMatrixSetLink"); element(w, "TileMatrixSet", MATRIX_SET);
                w.writeStartElement("TileMatrixSetLimits");
                for (int z = tiles.minZoom(); z <= tiles.maxZoom(); z++) {
                    Range range = tiles.range(z);
                    w.writeStartElement("TileMatrixLimits"); element(w, "TileMatrix", z);
                    element(w, "MinTileRow", range.minY()); element(w, "MaxTileRow", range.maxY());
                    element(w, "MinTileCol", range.minX()); element(w, "MaxTileCol", range.maxX());
                    w.writeEndElement();
                }
                w.writeEndElement(); w.writeEndElement();
                w.writeEmptyElement("ResourceURL"); w.writeAttribute("format", "image/png");
                w.writeAttribute("resourceType", "tile");
                w.writeAttribute("template", root + "wmts/1.0.0/default/" + MATRIX_SET + "/{TileMatrix}/{TileRow}/{TileCol}.png");
                w.writeEndElement();
                w.writeStartElement("TileMatrixSet"); element(w, "ows:Identifier", MATRIX_SET);
                element(w, "ows:SupportedCRS", "urn:ogc:def:crs:EPSG::3857");
                for (int z = tiles.minZoom(); z <= tiles.maxZoom(); z++) {
                    w.writeStartElement("TileMatrix"); element(w, "ows:Identifier", z);
                    element(w, "ScaleDenominator", resolution(z) / 0.00028);
                    element(w, "TopLeftCorner", -HALF_WORLD + " " + HALF_WORLD);
                    element(w, "TileWidth", 256); element(w, "TileHeight", 256);
                    element(w, "MatrixWidth", 1 << z); element(w, "MatrixHeight", 1 << z);
                    w.writeEndElement();
                }
                w.writeEndElement(); w.writeEndElement();
            }
            w.writeEmptyElement("ServiceMetadataURL"); w.writeAttribute("xlink:href", root + "wmts/1.0.0/WMTSCapabilities.xml");
            w.writeEndElement();
        });
    }

    /** 单个发布服务也提供完整 TMS 发现链，图层范围使用投影坐标，行号按左下原点解释。 */
    public String tmsDescriptor(String code, String resource) {
        Published source = source(code);
        String root = records.buildServiceRoot(type(), source.publication().getServiceCode()) + "tms/";
        String name = source.publication().getServiceName();
        Tiles tiles = source.tiles();
        return xml(w -> {
            if ("root".equals(resource)) {
                w.writeStartElement("Services"); w.writeEmptyElement("TileMapService");
                w.writeAttribute("title", name); w.writeAttribute("version", "1.0.0");
                w.writeAttribute("href", root + "1.0.0/"); w.writeEndElement();
            } else if ("service".equals(resource)) {
                w.writeStartElement("TileMapService"); w.writeAttribute("version", "1.0.0");
                w.writeAttribute("services", root); element(w, "Title", name);
                w.writeStartElement("TileMaps"); w.writeEmptyElement("TileMap");
                w.writeAttribute("title", name); w.writeAttribute("srs", "EPSG:3857");
                w.writeAttribute("profile", "none"); w.writeAttribute("href", root + "1.0.0/tilemap.xml");
                w.writeEndElement(); w.writeEndElement();
            } else {
                w.writeStartElement("TileMap"); w.writeAttribute("version", "1.0.0");
                w.writeAttribute("tilemapservice", root + "1.0.0/"); element(w, "Title", name);
                element(w, "Abstract", "矢量配图静态 PNG 瓦片"); element(w, "SRS", "EPSG:3857");
                w.writeEmptyElement("BoundingBox");
                w.writeAttribute("minx", Double.toString(tiles.bounds()[0] / 180 * HALF_WORLD));
                w.writeAttribute("miny", Double.toString(mercatorY(tiles.bounds()[1])));
                w.writeAttribute("maxx", Double.toString(tiles.bounds()[2] / 180 * HALF_WORLD));
                w.writeAttribute("maxy", Double.toString(mercatorY(tiles.bounds()[3])));
                w.writeEmptyElement("Origin"); w.writeAttribute("x", Double.toString(-HALF_WORLD));
                w.writeAttribute("y", Double.toString(-HALF_WORLD));
                w.writeEmptyElement("TileFormat"); w.writeAttribute("width", "256"); w.writeAttribute("height", "256");
                w.writeAttribute("mime-type", "image/png"); w.writeAttribute("extension", "png");
                // 显式声明各级分辨率；不冒用早期 global-mercator 的四瓦片零级定义。
                w.writeStartElement("TileSets"); w.writeAttribute("profile", "none");
                for (int z = tiles.minZoom(); z <= tiles.maxZoom(); z++) {
                    w.writeEmptyElement("TileSet"); w.writeAttribute("href", root + "1.0.0/" + z);
                    w.writeAttribute("units-per-pixel", Double.toString(resolution(z)));
                    w.writeAttribute("order", Integer.toString(z));
                }
                w.writeEndElement(); w.writeEndElement();
            }
        });
    }

    public static String exceptionXml(WmtsException exception, boolean tms) {
        return xml(w -> {
            if (tms) {
                w.writeStartElement("TileMapServerError"); element(w, "Message", exception.getMessage());
                w.writeEndElement(); return;
            }
            w.writeStartElement("ows:ExceptionReport"); w.writeNamespace("ows", OWS_NS);
            w.writeAttribute("version", "1.1.0"); w.writeStartElement("ows:Exception");
            w.writeAttribute("exceptionCode", exception.code); w.writeAttribute("locator", exception.locator);
            element(w, "ows:ExceptionText", exception.getMessage()); w.writeEndElement(); w.writeEndElement();
        });
    }

    private static WmtsException invalid(String parameter) {
        return new WmtsException("InvalidParameterValue", parameter, "参数无效: " + parameter);
    }

    private static double resolution(int z) { return 2 * HALF_WORLD / 256 / (1 << z); }
    private static double mercatorY(double latitude) {
        return Math.max(-HALF_WORLD, Math.min(HALF_WORLD,
                6378137 * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(latitude) / 2))));
    }

    private static String xml(XmlContent content) {
        try {
            StringWriter result = new StringWriter();
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(result);
            writer.writeStartDocument("UTF-8", "1.0"); content.write(writer);
            writer.writeEndDocument(); writer.close();
            return result.toString();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("生成瓦片服务描述失败", ex);
        }
    }

    private static void element(XMLStreamWriter writer, String name, Object value) throws XMLStreamException {
        writer.writeStartElement(name); writer.writeCharacters(value.toString()); writer.writeEndElement();
    }

    @FunctionalInterface private interface XmlContent { void write(XMLStreamWriter writer) throws XMLStreamException; }
    public record WmtsResource(String xml, Path tile) { }
    private record Published(GisPublication publication, Tiles tiles) { }
    private record Range(int minX, int maxX, int minY, int maxY) { }
    private record Tiles(Path root, int minZoom, int maxZoom, double[] bounds) {
        Range range(int z) {
            int n = 1 << z;
            int minX = Math.max(0, (int) Math.floor((bounds[0] + 180) / 360 * n));
            int maxX = Math.min(n - 1, (int) Math.ceil((bounds[2] + 180) / 360 * n) - 1);
            // 与生成器使用相同公式，确保边界瓦片和能力文档的有效范围一致。
            double north = Math.toRadians(bounds[3]), south = Math.toRadians(bounds[1]);
            int minY = Math.max(0, (int) Math.floor((1 - Math.log(Math.tan(north) + 1 / Math.cos(north)) / Math.PI) / 2 * n));
            int maxY = Math.min(n - 1, (int) Math.ceil((1 - Math.log(Math.tan(south) + 1 / Math.cos(south)) / Math.PI) / 2 * n) - 1);
            return new Range(minX, Math.max(minX, maxX), minY, Math.max(minY, maxY));
        }
    }

    public static class WmtsException extends RuntimeException {
        private final String code;
        private final String locator;
        private final HttpStatus status;
        WmtsException(String code, String locator, String message) {
            this(code, locator, message, HttpStatus.BAD_REQUEST);
        }
        WmtsException(String code, String locator, String message, HttpStatus status) {
            super(message); this.code = code; this.locator = locator; this.status = status;
        }
        public HttpStatus status() { return status; }
    }
}
