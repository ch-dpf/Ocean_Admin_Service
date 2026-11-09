package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisOgcService;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisOgcServiceMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
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
 * 读取独立 OGC 服务的切片元数据，供 WMTS、WMS、TMS 生成能力文档和定位瓦片。
 *
 * <p>图层清单唯一来源是 gis_ogc_service 中已启用记录；元数据仍取自瓦片目录下的
 * tilejson.json，与切片引擎保持一致。</p>
 */
@Slf4j
@Service
public class PublishedImageryCatalog {

    private static final String DESCRIPTOR = "tilejson.json";
    private static final String READY = "READY";

    private final GisOgcServiceMapper ogcServiceMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisProcessingStorageService storageService;
    private final ObjectMapper objectMapper;

    public PublishedImageryCatalog(GisOgcServiceMapper ogcServiceMapper,
            GisTileSetMapper tileSetMapper,
            GisProcessingStorageService storageService,
            ObjectMapper objectMapper) {
        this.ogcServiceMapper = ogcServiceMapper;
        this.tileSetMapper = tileSetMapper;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /** 列出指定协议的全部已启用图层；单个图层元数据异常时跳过，不影响其它图层。 */
    public List<PublishedLayer> listPublished(OgcProtocol protocol) {
        List<PublishedLayer> layers = new ArrayList<>();
        for (GisOgcService service : ogcServiceMapper.selectPublishedByProtocol(protocol.name())) {
            PublishedLayer layer = load(service);
            if (layer != null) {
                layers.add(layer.withLayerName(service.getLayerName()));
            }
        }
        return layers;
    }

    /**
     * 按服务编码与协议加载图层。
     *
     * @return 图层元数据；服务不存在、已停用、产物不可用或元数据非法时返回 null
     */
    public PublishedLayer load(String serviceCode, OgcProtocol protocol) {
        GisOgcService service = ogcServiceMapper.selectPublishedByCode(
                serviceCode, protocol.name());
        if (service == null) {
            return null;
        }
        GisTileSet tileSet = tileSetMapper.selectById(service.getTileSetId());
        if (tileSet == null) {
            return null;
        }
        PublishedLayer layer = load(serviceCode, protocol, tileSet);
        if (layer == null) {
            return null;
        }
        if (service.getMinZoom() < layer.minZoom()
                || service.getMaxZoom() > layer.maxZoom()
                || service.getMinZoom() > service.getMaxZoom()) {
            log.debug("[OGC][{}] 跳过层级配置非法的图层 {}", protocol, serviceCode);
            return null;
        }
        return new PublishedLayer(layer.serviceCode(), layer.protocol(), layer.matrixSet(),
                layer.tileProfile(), layer.extension(), layer.mediaType(),
                service.getMinZoom(), service.getMaxZoom(), layer.wgs84Bounds(),
                layer.tileSet(), service.getLayerName());
    }

    /** 读取瓦片集目录下的 tilejson.json，组装能力文档所需的图层元数据。 */
    public PublishedLayer load(String serviceCode, OgcProtocol protocol, GisTileSet tileSet) {
        if (tileSet == null || !READY.equals(tileSet.getTileSetStatus())) {
            return null;
        }
        try {
            Path descriptor = storageService.resolveTiles(tileSet.getOutputKey())
                    .resolve(DESCRIPTOR);
            if (!Files.isRegularFile(descriptor)) {
                return null;
            }
            return readLayer(serviceCode, protocol, tileSet, descriptor);
        } catch (RuntimeException ex) {
            log.debug("[OGC][{}] 跳过不可用图层 {}: {}", protocol, serviceCode, ex.getMessage());
            return null;
        }
    }

    private PublishedLayer load(GisOgcService service) {
        GisTileSet tileSet = tileSetMapper.selectById(service.getTileSetId());
        if (tileSet == null) {
            log.debug("[OGC][{}] 跳过瓦片集不存在的图层 {}", service.getProtocol(), service.getServiceCode());
            return null;
        }
        PublishedLayer layer = load(service.getServiceCode(), service.getProtocol(), tileSet);
        if (layer == null) {
            return null;
        }
        if (service.getMinZoom() < layer.minZoom()
                || service.getMaxZoom() > layer.maxZoom()
                || service.getMinZoom() > service.getMaxZoom()) {
            log.debug("[OGC][{}] 跳过层级配置非法的图层 {}", service.getProtocol(), service.getServiceCode());
            return null;
        }
        return new PublishedLayer(layer.serviceCode(), layer.protocol(), layer.matrixSet(),
                layer.tileProfile(), layer.extension(), layer.mediaType(),
                service.getMinZoom(), service.getMaxZoom(), layer.wgs84Bounds(),
                layer.tileSet(), service.getLayerName());
    }

    private PublishedLayer readLayer(String serviceCode, OgcProtocol protocol,
            GisTileSet tileSet, Path descriptor) {
        JsonNode tileJson;
        try {
            tileJson = objectMapper.readTree(descriptor.toFile());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("无法读取图层描述文件: " + descriptor, ex);
        }
        String crs = tileJson.path("crs").asText();
        String declaredMatrixSet = tileJson.path("tileMatrixSet").asText();
        TileMatrixSetDefinition matrixSet = matrixSetOf(crs, declaredMatrixSet);
        if (!declaredMatrixSet.isBlank() && !declaredMatrixSet.equalsIgnoreCase(matrixSet.id())) {
            throw new IllegalStateException("图层坐标系与瓦片矩阵集不一致: " + serviceCode);
        }
        String format = tileJson.path("format").asText().toLowerCase(Locale.ROOT);
        String extension = format.startsWith("jpeg") || format.startsWith("jpg") ? "jpg" : "png";
        String mediaType = "jpg".equals(extension) ? "image/jpeg" : "image/png";
        int minZoom = tileJson.path("minzoom").asInt(-1);
        int maxZoom = tileJson.path("maxzoom").asInt(-1);
        if (minZoom < 0 || maxZoom < minZoom) {
            throw new IllegalStateException("图层层级范围不合法: " + serviceCode);
        }
        JsonNode boundsNode = tileJson.path("bounds");
        if (!boundsNode.isArray() || boundsNode.size() != 4) {
            throw new IllegalStateException("图层地理范围不合法: " + serviceCode);
        }
        double[] bounds = new double[4];
        for (int i = 0; i < 4; i++) {
            bounds[i] = boundsNode.get(i).asDouble();
        }
        return new PublishedLayer(serviceCode, protocol, matrixSet,
                tileJson.path("scheme").asText(), extension, mediaType,
                minZoom, maxZoom, bounds, tileSet, null);
    }

    private TileMatrixSetDefinition matrixSetOf(String crs, String declaredMatrixSet) {
        TileMatrixSetDefinition byMatrixSet = TileMatrixSetDefinition.resolve(declaredMatrixSet);
        if (byMatrixSet != null) {
            return byMatrixSet;
        }
        TileMatrixSetDefinition byCrs = TileMatrixSetDefinition.resolveByCrs(crs);
        if (byCrs == null) {
            throw new IllegalStateException("不支持的图层坐标系: " + crs);
        }
        return byCrs;
    }

    /**
     * 一个已发布 OGC 图层的对外元数据。
     *
     * @param serviceCode 服务编码，同时作为 WMTS/WMS/TMS 的 LAYER 标识
     * @param protocol    服务协议
     * @param matrixSet   瓦片矩阵集
     * @param tileProfile 切片存储剖面，XYZ 或 TMS
     * @param extension   瓦片文件扩展名
     * @param mediaType   瓦片响应媒体类型
     * @param minZoom     产物最小层级
     * @param maxZoom     产物最大层级
     * @param wgs84Bounds WGS84 地理范围，顺序为 minX、minY、maxX、maxY
     * @param tileSet     关联的影像瓦片集，供公开资源定位与 WMS 源文件解析
     */
    public record PublishedLayer(
            String serviceCode,
            OgcProtocol protocol,
            TileMatrixSetDefinition matrixSet,
            String tileProfile,
            String extension,
            String mediaType,
            int minZoom,
            int maxZoom,
            double[] wgs84Bounds,
            GisTileSet tileSet,
            String layerName) {

        /** 为能力文档补上发布时填写的展示名。 */
        public PublishedLayer withLayerName(String layerName) {
            return new PublishedLayer(serviceCode, protocol, matrixSet, tileProfile,
                    extension, mediaType, minZoom, maxZoom, wgs84Bounds, tileSet, layerName);
        }
    }
}
