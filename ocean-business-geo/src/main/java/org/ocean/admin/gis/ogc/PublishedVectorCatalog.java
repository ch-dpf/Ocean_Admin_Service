package org.ocean.admin.gis.ogc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取已发布矢量服务的固定数据版本与样式方案，供 WMS、WMTS、TMS 生成能力文档和出图。
 *
 * <p>图层清单来源是 gis_publication 中带 vector_resource_id 的已启用记录；数据和样式
 * 全部来自发布时冻结的 gis_vector_resource 与发布绑定 JSON，不读取当前工作区图层。</p>
 */
@Slf4j
@Service
public class PublishedVectorCatalog {

    private static final String PUBLISHED = "PUBLISHED";
    private static final double[] WORLD_BOUNDS = {-180d, -85.05112877980659d, 180d, 85.05112877980659d};

    private final GisPublicationMapper publicationMapper;
    private final GisVectorMapper vectorMapper;
    private final ObjectMapper objectMapper;

    public PublishedVectorCatalog(GisPublicationMapper publicationMapper,
            GisVectorMapper vectorMapper, ObjectMapper objectMapper) {
        this.publicationMapper = publicationMapper;
        this.vectorMapper = vectorMapper;
        this.objectMapper = objectMapper;
    }

    /** 列出全部已发布矢量图层；单条记录元数据异常时跳过，不影响其它图层。 */
    public List<PublishedVectorLayer> listPublished() {
        List<GisPublication> publications = publicationMapper.selectList(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getStatus, PUBLISHED)
                        .isNotNull(GisPublication::getVectorResourceId)
                        .orderByDesc(GisPublication::getPublishTime));
        List<PublishedVectorLayer> layers = new ArrayList<>();
        for (GisPublication publication : publications) {
            PublishedVectorLayer layer = load(publication, null);
            if (layer != null) {
                layers.add(layer);
            }
        }
        return layers;
    }

    /** 按服务编码加载默认样式图层；不存在或已停用时返回 null。 */
    public PublishedVectorLayer load(String serviceCode) {
        return load(serviceCode, null);
    }

    /**
     * 按服务编码与样式方案加载图层。
     *
     * @param styleId 样式方案 ID；为空时使用发布默认样式，指定不存在的方案时返回 null
     */
    public PublishedVectorLayer load(String serviceCode, String styleId) {
        if (serviceCode == null || serviceCode.isBlank()) {
            return null;
        }
        GisPublication publication = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getServiceCode, serviceCode.trim())
                        .eq(GisPublication::getStatus, PUBLISHED)
                        .isNotNull(GisPublication::getVectorResourceId));
        if (publication == null) {
            return null;
        }
        return load(publication, styleId);
    }

    private PublishedVectorLayer load(GisPublication publication, String styleId) {
        try {
            GisVectorResource resource = vectorMapper.resource(publication.getVectorResourceId());
            if (resource == null || !"READY".equals(resource.getStatus())
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
            JsonNode scheme = findScheme(binding.path("styles"), chosenId);
            if (scheme == null) {
                return null;
            }
            JsonNode cartography = scheme.path("definition").path("cartography");
            if (!cartography.path("groups").isObject() || !cartography.path("layers").isObject()
                    || !cartography.path("defaults").path("styles").isArray()) {
                return null;
            }
            return new PublishedVectorLayer(publication.getServiceCode(),
                    publication.getServiceName() == null
                            ? publication.getServiceCode() : publication.getServiceName(),
                    binding.path("releaseVersion").asText(), publication, resource,
                    bounds(publication.getDataSetId()), binding, contract, scheme,
                    cartography, styleOptions(binding.path("styles")), chosenId);
        } catch (RuntimeException ex) {
            log.debug("[OGC][VECTOR] 跳过不可用图层 {}: {}", publication.getServiceCode(), ex.getMessage());
            return null;
        }
    }

    private JsonNode findScheme(JsonNode styles, String styleId) {
        for (JsonNode scheme : styles) {
            if (styleId.equals(scheme.path("id").asText())) {
                return scheme;
            }
        }
        return null;
    }

    private List<StyleOption> styleOptions(JsonNode styles) {
        List<StyleOption> options = new ArrayList<>();
        for (JsonNode scheme : styles) {
            options.add(new StyleOption(scheme.path("id").asText(), scheme.path("name").asText("")));
        }
        return List.copyOf(options);
    }

    /** 数据集当前要素范围；无有效范围时退回 Web 墨卡托全球范围，保证能力文档可用。 */
    private double[] bounds(Long dataSetId) {
        if (dataSetId == null) {
            return WORLD_BOUNDS.clone();
        }
        try {
            String extent = vectorMapper.dataSetExtent(dataSetId);
            if (extent == null || extent.isBlank()) {
                return WORLD_BOUNDS.clone();
            }
            JsonNode ring = objectMapper.readTree(extent)
                    .path("coordinates").get(0);
            if (ring == null || !ring.isArray() || ring.isEmpty()) {
                return WORLD_BOUNDS.clone();
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (JsonNode point : ring) {
                double x = point.get(0).asDouble();
                double y = point.get(1).asDouble();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                    || minX >= maxX || minY >= maxY) {
                return WORLD_BOUNDS.clone();
            }
            return new double[]{minX, minY, maxX, maxY};
        } catch (RuntimeException ex) {
            log.debug("[OGC][VECTOR] 数据集 {} 范围不可用，使用全球范围: {}", dataSetId, ex.getMessage());
            return WORLD_BOUNDS.clone();
        }
    }

    /**
     * 能力文档视图：一个矢量服务在两种受支持矩阵集下各生成一个瓦片图层。
     *
     * <p>矢量瓦片按需动态光栅化，因此不像静态切片那样受产物坐标系限制，
     * WebMercatorQuad 与 WorldCRS84Quad 可以同时提供。</p>
     */
    public List<VectorLayerView> listViews() {
        List<VectorLayerView> views = new ArrayList<>();
        for (PublishedVectorLayer layer : listPublished()) {
            int minZoom = layer.contract().path("map").path("minZoom").asInt(0);
            int maxZoom = layer.contract().path("map").path("nativeMaxZoom").asInt(18);
            if (minZoom < 0 || maxZoom > TileMatrixSetDefinition.MAX_ZOOM || maxZoom < minZoom) {
                continue;
            }
            for (TileMatrixSetDefinition matrixSet : TileMatrixSetDefinition.values()) {
                views.add(new VectorLayerView(layer.serviceCode(), layer.serviceName(),
                        layer.wgs84Bounds(), minZoom, maxZoom, "png", "image/png",
                        matrixSet, layer.styles(), layer.styleId(), layer.releaseVersion()));
            }
        }
        return List.copyOf(views);
    }

    /**
     * 按能力文档中的瓦片图层标识加载视图。
     *
     * <p>标识形如 {@code 服务编码@矩阵集}；矩阵集省略时按 WebMercatorQuad 处理，
     * 让已发布服务编码本身也能作为简写使用。</p>
     */
    public VectorLayerView findView(String layerId) {
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
        for (VectorLayerView view : listViews()) {
            if (serviceCode.equals(view.serviceCode()) && matrixSet == view.matrixSet()) {
                return view;
            }
        }
        return null;
    }

    /** 按服务编码与矩阵集标识定位视图，用于 WMTS 的 TILEMATRIXSET 校验。 */
    public VectorLayerView findView(String serviceCode, String matrixSetId) {
        TileMatrixSetDefinition matrixSet = TileMatrixSetDefinition.resolve(matrixSetId);
        if (matrixSet == null) {
            return null;
        }
        return findView(serviceCode + "@" + matrixSet.id());
    }

    /** 能力文档与瓦片定位共用的矢量瓦片图层描述。 */
    public record VectorLayerView(
            String serviceCode,
            String title,
            double[] wgs84Bounds,
            int minZoom,
            int maxZoom,
            String extension,
            String mediaType,
            TileMatrixSetDefinition matrixSet,
            List<StyleOption> styles,
            String defaultStyleId,
            String releaseVersion) {
    }

    /** 能力文档中列出的一套可切换样式。 */
    public record StyleOption(String id, String name) {
    }

    /**
     * 一个已发布矢量图层的固定渲染契约。
     *
     * @param serviceCode    服务编码，同时作为 OGC 请求的 LAYER 标识
     * @param serviceName    发布时填写的展示名
     * @param releaseVersion 样式发布版本，参与瓦片缓存键
     * @param publication    发布记录
     * @param resource       冻结的 GPKG 数据版本
     * @param wgs84Bounds    WGS84 范围，顺序为 minX、minY、maxX、maxY
     * @param binding        发布绑定（默认样式、样式方案列表）
     * @param contract       冻结契约（layers、groups、tilePolicy）
     * @param scheme         本次请求使用的样式方案
     * @param cartography    样式方案中的分类、图层、默认模板样式配置
     * @param styles         该服务全部可切换样式
     * @param styleId        本次请求实际使用的样式方案 ID
     */
    public record PublishedVectorLayer(
            String serviceCode,
            String serviceName,
            String releaseVersion,
            GisPublication publication,
            GisVectorResource resource,
            double[] wgs84Bounds,
            JsonNode binding,
            JsonNode contract,
            JsonNode scheme,
            JsonNode cartography,
            List<StyleOption> styles,
            String styleId) {

        /** 冻结版本中的全部工作图层；每个文件组的每个分类各自一条。 */
        public JsonNode layers() {
            return contract.path("layers");
        }

        /** 运行时分类 ID 到模板业务键的映射。 */
        public String groupKey(long groupId) {
            for (JsonNode group : contract.path("groups")) {
                if (group.path("id").asLong() == groupId) {
                    return group.path("groupKey").asText();
                }
            }
            return "";
        }

        /** 模板样式 ID 到样式定义；defaults.styles 与数据集模板的 cartography.styles 一致。 */
        public JsonNode baseStyle(String styleKey) {
            for (JsonNode style : cartography.path("defaults").path("styles")) {
                if (styleKey.equals(style.path("id").asText())) {
                    return style;
                }
            }
            return null;
        }

        /** 模板分类的默认样式键；冻结分组保留发布时的分类映射。 */
        public String defaultStyleKey(long groupId) {
            for (JsonNode group : contract.path("groups")) {
                if (group.path("id").asLong() == groupId) {
                    return group.path("defaultStyleKey").asText("");
                }
            }
            return "";
        }
    }
}
