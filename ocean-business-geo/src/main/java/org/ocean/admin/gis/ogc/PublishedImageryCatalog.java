package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationRecordService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 读取已发布影像服务的切片元数据，供 OGC 协议层生成能力文档和定位瓦片。
 *
 * <p>元数据唯一来源是瓦片集目录下的 {@code tilejson.json}，不读取切片文件本身。</p>
 */
@Slf4j
@Service
public class PublishedImageryCatalog {

    private static final int PAGE_SIZE = 100;
    private static final String DESCRIPTOR = "tilejson.json";
    private static final String READY = "READY";

    private final PublicationRecordService recordService;
    private final GisProcessingStorageService storageService;
    private final ObjectMapper objectMapper;

    public PublishedImageryCatalog(PublicationRecordService recordService,
            GisProcessingStorageService storageService,
            ObjectMapper objectMapper) {
        this.recordService = recordService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /** 列出全部已启用图层；单个图层元数据异常时跳过，不影响其它图层。 */
    public List<PublishedLayer> listPublished() {
        List<PublishedLayer> layers = new ArrayList<>();
        for (long current = 1; ; current++) {
            var page = recordService.getPublicationPage(
                    GisProcessingType.IMAGERY, (int) current, PAGE_SIZE,
                    null, PublicationRecordService.PUBLISHED, null, null, null);
            List<GisPublication> records = page.getRecords();
            for (GisPublication record : records) {
                PublishedLayer layer = load(record);
                if (layer != null) {
                    layers.add(layer);
                }
            }
            if (records.size() < PAGE_SIZE || current * PAGE_SIZE >= page.getTotal()) {
                return layers;
            }
        }
    }

    /**
     * 按服务编码加载图层。
     *
     * @return 图层元数据；服务不存在、已停用或元数据非法时返回 null
     */
    public PublishedLayer load(String serviceCode) {
        try {
            return load(recordService.getRequired(GisProcessingType.IMAGERY, serviceCode));
        } catch (RuntimeException ex) {
            log.debug("[OGC][WMTS] 跳过不可用图层 {}: {}", serviceCode, ex.getMessage());
            return null;
        }
    }

    /**
     * 使用调用方已加载的瓦片集加载图层，避免发布视图重复查询发布记录与瓦片集。
     *
     * @param serviceCode 发布服务编码，同时作为 WMTS 图层标识
     */
    public PublishedLayer load(GisTileSet tileSet, String serviceCode) {
        if (tileSet == null || !READY.equals(tileSet.getTileSetStatus())) {
            return null;
        }
        try {
            Path descriptor = storageService.resolveTiles(tileSet.getOutputKey())
                    .resolve(DESCRIPTOR);
            if (!Files.isRegularFile(descriptor)) {
                return null;
            }
            return readLayer(serviceCode, descriptor);
        } catch (RuntimeException ex) {
            log.debug("[OGC][WMTS] 跳过不可用图层 {}: {}", serviceCode, ex.getMessage());
            return null;
        }
    }

    private PublishedLayer load(GisPublication publication) {
        if (!PublicationRecordService.PUBLISHED.equals(publication.getStatus())) {
            return null;
        }
        try {
            return load(recordService.getPublicationTileSet(publication),
                    publication.getServiceCode());
        } catch (RuntimeException ex) {
            log.debug("[OGC][WMTS] 跳过不可用图层 {}: {}",
                    publication.getServiceCode(), ex.getMessage());
            return null;
        }
    }

    private PublishedLayer readLayer(String serviceCode, Path descriptor) {
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
        return new PublishedLayer(serviceCode, matrixSet, tileJson.path("scheme").asText(),
                extension, mediaType, minZoom, maxZoom, bounds);
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
     * 一个已发布影像图层的对外元数据。
     *
     * @param serviceCode 服务编码，同时作为 WMTS 图层标识
     * @param matrixSet   瓦片矩阵集
     * @param tileProfile 切片存储剖面，XYZ 或 TMS
     * @param extension   瓦片文件扩展名
     * @param mediaType   瓦片响应媒体类型
     * @param minZoom     最小层级
     * @param maxZoom     最大层级
     * @param wgs84Bounds WGS84 地理范围，顺序为 minX、minY、maxX、maxY
     */
    public record PublishedLayer(
            String serviceCode,
            TileMatrixSetDefinition matrixSet,
            String tileProfile,
            String extension,
            String mediaType,
            int minZoom,
            int maxZoom,
            double[] wgs84Bounds) {
    }
}
