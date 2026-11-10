package org.ocean.admin.gis.ogc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 统一发布目录：只读取 gis_publication 中已发布且声明对应协议的记录。
 *
 * <p>静态瓦片元数据仍来自产物描述文件，动态矢量元数据来自冻结 GPKG 和样式绑定 JSON。</p>
 */
@Slf4j
@Service
public class PublicationCatalog {

    private static final String PUBLISHED = "PUBLISHED";
    private static final String READY = "READY";
    private static final double[] WORLD_BOUNDS =
            {-180d, -85.05112877980659d, 180d, 85.05112877980659d};

    private final GisPublicationMapper publicationMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisVectorMapper vectorMapper;
    private final GisProcessingStorageService storageService;
    private final ObjectMapper objectMapper;

    public PublicationCatalog(GisPublicationMapper publicationMapper,
            GisTileSetMapper tileSetMapper, GisVectorMapper vectorMapper,
            GisProcessingStorageService storageService, ObjectMapper objectMapper) {
        this.publicationMapper = publicationMapper;
        this.tileSetMapper = tileSetMapper;
        this.vectorMapper = vectorMapper;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /** 列出指定协议的全部静态瓦片图层。 */
    public List<PublicationLayer.RasterLayer> listRaster(OgcProtocol protocol) {
        List<PublicationLayer.RasterLayer> layers = new ArrayList<>();
        for (GisPublication publication : listPublished(protocol)) {
            if (publication.getTileSetId() == null) {
                continue;
            }
            PublicationLayer.RasterLayer layer = loadRaster(publication);
            if (layer != null) {
                layers.add(layer);
            }
        }
        return layers;
    }

    /** 按服务编码与协议加载静态瓦片图层。 */
    public PublicationLayer.RasterLayer loadRaster(String serviceCode, OgcProtocol protocol) {
        GisPublication publication = selectPublished(serviceCode, protocol);
        if (publication == null || publication.getTileSetId() == null) {
            return null;
        }
        return loadRaster(publication);
    }

    /** 列出指定协议的全部动态矢量图层。 */
    public List<PublicationLayer.VectorLayer> listVector(OgcProtocol protocol) {
        List<PublicationLayer.VectorLayer> layers = new ArrayList<>();
        for (GisPublication publication : listPublished(protocol)) {
            if (publication.getVectorResourceId() == null) {
                continue;
            }
            PublicationLayer.VectorLayer layer = loadVector(publication, null);
            if (layer != null) {
                layers.add(layer);
            }
        }
        return layers;
    }

    /** 按服务编码加载动态矢量图层，styleId 为空时使用发布默认样式。 */
    public PublicationLayer.VectorLayer loadVector(String serviceCode, String styleId) {
        GisPublication publication = selectPublished(serviceCode, null);
        if (publication == null || publication.getVectorResourceId() == null) {
            return null;
        }
        return loadVector(publication, styleId);
    }

    /** 动态矢量服务在两种受支持矩阵集下各生成一个瓦片视图。 */
    public List<PublicationLayer.VectorLayerView> listVectorViews(OgcProtocol protocol) {
        List<PublicationLayer.VectorLayerView> views = new ArrayList<>();
        for (PublicationLayer.VectorLayer layer : listVector(protocol)) {
            int minZoom = layer.contract().path("map").path("minZoom").asInt(0);
            int maxZoom = layer.contract().path("map").path("nativeMaxZoom").asInt(18);
            if (minZoom < 0 || maxZoom > TileMatrixSetDefinition.MAX_ZOOM || maxZoom < minZoom) {
                continue;
            }
            for (TileMatrixSetDefinition matrixSet : TileMatrixSetDefinition.values()) {
                views.add(new PublicationLayer.VectorLayerView(layer.serviceCode(), layer.serviceName(),
                        layer.wgs84Bounds(), minZoom, maxZoom, "png", "image/png",
                        matrixSet, layer.styles(), layer.styleId(), layer.releaseVersion()));
            }
        }
        return List.copyOf(views);
    }

    /** 按能力文档中的服务编码或 服务编码@矩阵集 标识定位视图。 */
    public PublicationLayer.VectorLayerView findView(String layerId) {
        if (layerId == null || layerId.isBlank()) {
            return null;
        }
        String[] parts = layerId.trim().split("@", -1);
        if (parts.length > 2 || parts[0].isBlank()) {
            return null;
        }
        TileMatrixSetDefinition matrixSet = parts.length == 1
                ? TileMatrixSetDefinition.WEB_MERCATOR_QUAD
                : TileMatrixSetDefinition.resolve(parts[1].trim());
        if (matrixSet == null) {
            return null;
        }
        String serviceCode = parts[0].trim();
        for (PublicationLayer.VectorLayerView view : listVectorViews(OgcProtocol.WMTS)) {
            if (serviceCode.equals(view.serviceCode()) && matrixSet == view.matrixSet()) {
                return view;
            }
        }
        return null;
    }

    /** 按服务编码与矩阵集标识定位 WMTS/TMS 视图。 */
    public PublicationLayer.VectorLayerView findView(String serviceCode, String matrixSetId) {
        TileMatrixSetDefinition matrixSet = TileMatrixSetDefinition.resolve(matrixSetId);
        if (matrixSet == null) {
            return null;
        }
        return findView(serviceCode + "@" + matrixSet.id());
    }

    /** 按服务编码和协议定位静态瓦片文件。 */
    public Path resolveTileFile(String serviceCode, OgcProtocol protocol, String relativePath) {
        GisTileSet tileSet = publishedTileSet(serviceCode, protocol);
        if (tileSet == null) {
            throw new IllegalArgumentException("OGC 服务不存在或已停用: " + serviceCode);
        }
        Path root = storageService.resolveTiles(tileSet.getOutputKey());
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("瓦片不存在: " + relativePath);
        }
        return file;
    }

    /** 供 WMS 源解析与静态资源定位使用。 */
    public GisTileSet publishedTileSet(String serviceCode, OgcProtocol protocol) {
        GisPublication publication = selectPublished(serviceCode, protocol);
        if (publication == null || publication.getTileSetId() == null) {
            return null;
        }
        return tileSetMapper.selectById(publication.getTileSetId());
    }

    private List<GisPublication> listPublished(OgcProtocol protocol) {
        return publicationMapper.selectList(new LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getStatus, PUBLISHED)
                .orderByDesc(GisPublication::getPublishTime))
                .stream().filter(publication -> hasProtocol(publication, protocol)).toList();
    }

    private GisPublication selectPublished(String serviceCode, OgcProtocol protocol) {
        if (serviceCode == null || serviceCode.isBlank()) {
            return null;
        }
        GisPublication publication = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getServiceCode, serviceCode.trim())
                        .eq(GisPublication::getStatus, PUBLISHED));
        return publication != null && (protocol == null || hasProtocol(publication, protocol))
                ? publication : null;
    }

    private boolean hasProtocol(GisPublication publication, OgcProtocol protocol) {
        if (publication.getProtocolsJson() == null || publication.getProtocolsJson().isBlank()) {
            return false;
        }
        try {
            for (JsonNode value : objectMapper.readTree(publication.getProtocolsJson())) {
                if (protocol.name().equalsIgnoreCase(value.asText())) {
                    return true;
                }
            }
        } catch (RuntimeException ex) {
            log.debug("[OGC] 发布记录 {} 协议集合无法解析", publication.getServiceCode(), ex);
        }
        return false;
    }

    private PublicationLayer.RasterLayer loadRaster(GisPublication publication) {
        GisTileSet tileSet = tileSetMapper.selectById(publication.getTileSetId());
        if (tileSet == null || !READY.equals(tileSet.getTileSetStatus())) {
            return null;
        }
        try {
            Path descriptorPath = chooseDescriptor(tileSet);
            if (descriptorPath == null || !Files.isRegularFile(descriptorPath)) {
                return null;
            }
            JsonNode descriptor = objectMapper.readTree(descriptorPath.toFile());
            String crs = descriptor.path("crs").asText(
                    descriptor.path("targetCrs").asText(tileSet.getTargetCrs()));
            String declaredMatrixSet = descriptor.path("tileMatrixSet").asText(
                    descriptor.path("matrixSet").asText(""));
            TileMatrixSetDefinition matrixSet = TileMatrixSetDefinition.resolve(declaredMatrixSet);
            if (matrixSet == null) {
                matrixSet = TileMatrixSetDefinition.resolveByCrs(crs);
            }
            if (matrixSet == null) {
                return null;
            }
            String format = descriptor.path("format").asText(tileSet.getOutputFormat())
                    .toLowerCase(Locale.ROOT);
            String extension = format.startsWith("jpeg") || format.startsWith("jpg") ? "jpg" : "png";
            String mediaType = "jpg".equals(extension) ? "image/jpeg" : "image/png";
            int minZoom = descriptor.path("minzoom").asInt(descriptor.path("minZoom")
                    .asInt(tileSet.getMinZoom() == null ? 0 : tileSet.getMinZoom()));
            int maxZoom = descriptor.path("maxzoom").asInt(descriptor.path("maxZoom")
                    .asInt(tileSet.getMaxZoom() == null ? 0 : tileSet.getMaxZoom()));
            double[] bounds = boundsOf(descriptor, publication);
            return new PublicationLayer.RasterLayer(publication.getServiceCode(),
                    publication.getServiceName(), publication.getLayerName(), matrixSet,
                    tileSet.getTileProfile(), extension, mediaType,
                    publication.getMinZoom() == null ? minZoom : publication.getMinZoom(),
                    publication.getMaxZoom() == null ? maxZoom : publication.getMaxZoom(),
                    bounds, tileSet);
        } catch (RuntimeException ex) {
            log.debug("[OGC] 跳过不可用静态图层 {}: {}", publication.getServiceCode(), ex.getMessage());
            return null;
        }
    }

    private Path chooseDescriptor(GisTileSet tileSet) {
        Path root = storageService.resolveTiles(tileSet.getOutputKey());
        Path tileJson = root.resolve("tilejson.json");
        if (Files.isRegularFile(tileJson)) {
            return tileJson;
        }
        return root.resolve("manifest.json");
    }

    private double[] boundsOf(JsonNode descriptor, GisPublication publication) {
        JsonNode boundsNode = descriptor.path("bounds");
        if (!boundsNode.isArray() || boundsNode.size() != 4) {
            return parseBounds(publication.getBoundsWgs84Json(), WORLD_BOUNDS.clone());
        }
        return new double[]{boundsNode.get(0).asDouble(), boundsNode.get(1).asDouble(),
                boundsNode.get(2).asDouble(), boundsNode.get(3).asDouble()};
    }

    private double[] parseBounds(String value, double[] fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isArray() || node.size() != 4) {
                return fallback;
            }
            return new double[]{node.get(0).asDouble(), node.get(1).asDouble(),
                    node.get(2).asDouble(), node.get(3).asDouble()};
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private PublicationLayer.VectorLayer loadVector(GisPublication publication, String styleId) {
        try {
            GisVectorResource resource = vectorMapper.resource(publication.getVectorResourceId());
            if (resource == null || !READY.equals(resource.getStatus())
                    || !"GPKG".equals(resource.getStorageType())) {
                return null;
            }
            JsonNode binding = objectMapper.readTree(publication.getVectorDefinition());
            JsonNode contract = objectMapper.readTree(resource.getDefinitionJson());
            if (!contract.path("layers").isArray() || contract.path("layers").isEmpty()) {
                return null;
            }
            String chosenId = styleId == null || styleId.isBlank()
                    ? binding.path("defaultStyleId").asText() : styleId.trim();
            JsonNode scheme = null;
            for (JsonNode candidate : binding.path("styles")) {
                if (chosenId.equals(candidate.path("id").asText())) {
                    scheme = candidate;
                    break;
                }
            }
            if (scheme == null) {
                return null;
            }
            JsonNode cartography = scheme.path("definition").path("cartography");
            if (!cartography.path("groups").isObject() || !cartography.path("layers").isObject()
                    || !cartography.path("defaults").path("styles").isArray()) {
                return null;
            }
            List<PublicationLayer.StyleOption> styles = new ArrayList<>();
            for (JsonNode candidate : binding.path("styles")) {
                styles.add(new PublicationLayer.StyleOption(
                        candidate.path("id").asText(), candidate.path("name").asText("")));
            }
            return new PublicationLayer.VectorLayer(publication.getServiceCode(),
                    publication.getServiceName(), publication.getLayerName(),
                    binding.path("releaseVersion").asText(), resource,
                    parseBounds(publication.getBoundsWgs84Json(), vectorBounds(publication.getDataSetId())),
                    binding, contract, scheme, cartography, List.copyOf(styles), chosenId);
        } catch (RuntimeException ex) {
            log.debug("[OGC][VECTOR] 跳过不可用图层 {}: {}", publication.getServiceCode(), ex.getMessage());
            return null;
        }
    }

    private double[] vectorBounds(Long dataSetId) {
        if (dataSetId == null) {
            return WORLD_BOUNDS.clone();
        }
        try {
            String extent = vectorMapper.dataSetExtent(dataSetId);
            JsonNode ring = extent == null || extent.isBlank() ? null
                    : objectMapper.readTree(extent).path("coordinates").get(0);
            if (ring == null || !ring.isArray() || ring.isEmpty()) {
                return WORLD_BOUNDS.clone();
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (JsonNode point : ring) {
                minX = Math.min(minX, point.get(0).asDouble());
                minY = Math.min(minY, point.get(1).asDouble());
                maxX = Math.max(maxX, point.get(0).asDouble());
                maxY = Math.max(maxY, point.get(1).asDouble());
            }
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                    || minX >= maxX || minY >= maxY) {
                return WORLD_BOUNDS.clone();
            }
            return new double[]{minX, minY, maxX, maxY};
        } catch (RuntimeException ex) {
            return WORLD_BOUNDS.clone();
        }
    }
}
