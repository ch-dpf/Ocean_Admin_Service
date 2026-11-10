package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.UnifiedPublicationRequest;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.ogc.OgcProtocol;
import org.ocean.admin.gis.ogc.TileMatrixSetDefinition;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 按已完成任务统一发布多协议 GIS 服务。 */
@Service
@RequiredArgsConstructor
public class PublicationService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final String DISABLED = "DISABLED";
    private static final String READY = "READY";

    private final PublicationRecordService recordService;
    private final ProcessingTaskRecordService taskService;
    private final GisDataSetMapper dataSetMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisPublicationMapper publicationMapper;
    private final GisProcessingStorageService storageService;
    private final VectorResourceService vectorResources;
    private final VectorStyleService vectorStyles;
    private final ObjectMapper objectMapper;

    public PageResult<List<GisPublicationVO>> getPublicationPage(
            Integer current, Integer size, String processingType, String serviceCode,
            String status, Long dataSetId, LocalDateTime publishTimeStart,
            LocalDateTime publishTimeEnd) {
        PageResult<List<GisPublication>> page = recordService.getPublicationPage(
                normalizeProcessingType(processingType), current, size, serviceCode,
                status, dataSetId, publishTimeStart, publishTimeEnd);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::toVO).toList());
    }

    /** 按任务产物发布一条多协议记录；不兼容旧发布记录，不写 gis_ogc_service。 */
    @Transactional(rollbackFor = Exception.class)
    public GisPublicationVO publish(GisProcessingType processingType, Long sourceTaskId,
            UnifiedPublicationRequest request) throws IOException {
        GisProcessingTask task = taskService.getRequiredForUpdate(sourceTaskId);
        if (task.getProcessingType() != processingType) {
            throw new IllegalArgumentException("发布类型与任务处理类型不一致");
        }
        if (!"COMPLETED".equals(task.getTaskStatus().name())) {
            throw new IllegalStateException("只有 COMPLETED 状态的任务可以发布");
        }
        Set<OgcProtocol> protocols = OgcProtocol.validate(processingType.name(), request.protocols());
        Long dataSetId = commonDataSetId(task.getId());
        GisTileSet tileSet = tileSetMapper.selectByTaskId(task.getId());
        boolean dynamicVector = processingType == GisProcessingType.VECTOR
                && (tileSet == null || !"PNG".equalsIgnoreCase(tileSet.getOutputFormat()));
        GisVectorResource resource = null;
        String vectorDefinition = null;
        JsonNode descriptor = null;

        if (dynamicVector) {
            if (dataSetId == null) {
                throw new IllegalStateException("任务没有可发布的矢量数据集");
            }
            resource = vectorResources.freeze(dataSetId, "PUBLICATION");
            vectorDefinition = buildVectorDefinition(dataSetId, request);
        } else {
            if (tileSet == null || tileSet.getTileType() != processingType
                    || !READY.equals(tileSet.getTileSetStatus())) {
                throw new IllegalStateException("任务没有对应类型且已就绪的瓦片集");
            }
            descriptor = readDescriptor(tileSet);
        }

        int minZoom = request.minZoom() == null ? descriptorMin(descriptor, tileSet) : request.minZoom();
        int maxZoom = request.maxZoom() == null ? descriptorMax(descriptor, tileSet, resource) : request.maxZoom();
        if (minZoom < 0 || maxZoom < minZoom || maxZoom > TileMatrixSetDefinition.MAX_ZOOM) {
            throw new IllegalArgumentException("服务层级范围不合法");
        }

        String serviceCode = serviceCode(processingType);
        Map<String, String> serviceUrls = new LinkedHashMap<>();
        Map<String, String> capabilitiesUrls = new LinkedHashMap<>();
        Map<String, String> tileUrlTemplates = new LinkedHashMap<>();
        buildProtocolUrls(serviceCode, processingType, protocols,
                serviceUrls, capabilitiesUrls, tileUrlTemplates);

        LocalDateTime now = LocalDateTime.now();
        GisPublication publication = new GisPublication();
        publication.setServiceCode(serviceCode);
        publication.setServiceName(request.serviceName());
        publication.setServiceProtocol(primaryProtocol(processingType, dynamicVector));
        publication.setProtocolsJson(writeJson(protocols.stream().map(Enum::name).toList()));
        publication.setSourceTaskId(task.getId());
        publication.setProcessingType(processingType.name());
        publication.setLayerName(request.layerName());
        publication.setTitle(request.title() == null ? request.serviceName() : request.title());
        publication.setAbstractText(request.abstractText());
        publication.setKeywords(request.keywords());
        publication.setDefaultCrs(request.defaultCrs() == null
                ? targetCrs(descriptor, tileSet) : request.defaultCrs());
        publication.setSupportedCrsJson(writeJson(request.supportedCrs() == null
                ? List.of(publication.getDefaultCrs(), "CRS:84") : request.supportedCrs()));
        publication.setTileMatrixSetsJson(writeJson(request.tileMatrixSets() == null
                ? List.of("WebMercatorQuad") : request.tileMatrixSets()));
        publication.setMinZoom(minZoom);
        publication.setMaxZoom(maxZoom);
        publication.setTargetCrs(publication.getDefaultCrs());
        publication.setTileMatrixSet("WebMercatorQuad");
        publication.setOutputFormat(dynamicVector ? "PNG"
                : tileSet == null ? "PNG" : tileSet.getOutputFormat());
        publication.setTileProfile(tileSet == null ? "XYZ" : tileSet.getTileProfile());
        publication.setBoundsWgs84Json(writeJson(boundsOf(descriptor, tileSet)));
        publication.setStyleId(request.defaultStyleSchemeId() == null
                ? null : request.defaultStyleSchemeId().toString());
        publication.setStyleName(styleName(dataSetId, request.defaultStyleSchemeId()));
        publication.setPublicBaseUrl(recordService.publicBaseUrl());
        publication.setCapabilitiesUrlsJson(writeJson(capabilitiesUrls));
        publication.setServiceUrlsJson(writeJson(serviceUrls));
        publication.setTileUrlTemplatesJson(writeJson(tileUrlTemplates));
        publication.setTileSetId(dynamicVector ? null : tileSet.getId());
        publication.setVectorResourceId(dynamicVector ? resource.getId() : null);
        publication.setVectorDefinition(vectorDefinition);
        publication.setDataSetId(dataSetId);
        publication.setStatus(PUBLISHED);
        publication.setPublishTime(now);
        publication.setUpdateTime(now);
        publication.setDeleted(0);
        return toVO(recordService.saveUnified(publication));
    }

    public GisPublicationVO get(GisProcessingType processingType, String serviceCode) {
        return toVO(recordService.getRequired(processingType, serviceCode));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublicationVO rename(GisProcessingType processingType,
            String serviceCode, String serviceName) {
        return toVO(recordService.rename(processingType, serviceCode, serviceName));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublicationVO disable(GisProcessingType processingType, String serviceCode) {
        return toVO(recordService.disable(processingType, serviceCode, "GIS服务"));
    }

    /** 统一发布视图：JSON 文本列反序列化，旧单值地址字段从多协议 URL 映射派生。 */
    private GisPublicationVO toVO(GisPublication publication) {
        List<String> protocols = readStringList(publication.getProtocolsJson());
        Map<String, String> capabilitiesUrls = readStringMap(publication.getCapabilitiesUrlsJson());
        Map<String, String> serviceUrls = readStringMap(publication.getServiceUrlsJson());
        Map<String, String> tileUrlTemplates = readStringMap(publication.getTileUrlTemplatesJson());
        GisTileSet tileSet = publication.getTileSetId() == null ? null
                : tileSetMapper.selectById(publication.getTileSetId());
        GisDataSet dataSet = publication.getDataSetId() == null ? null
                : dataSetMapper.selectById(publication.getDataSetId());
        String taskName = null;
        if (publication.getSourceTaskId() != null) {
            GisProcessingTask task = taskService.getRequired(publication.getSourceTaskId());
            taskName = task.getTaskName();
        }
        String primary = protocols.stream().findFirst().orElse("WMS");
        return GisPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .serviceName(publication.getServiceName())
                .serviceProtocol(publication.getServiceProtocol())
                .protocols(protocols)
                .processingType(processingType(publication.getProcessingType()))
                .layerName(publication.getLayerName())
                .title(publication.getTitle())
                .abstractText(publication.getAbstractText())
                .keywords(publication.getKeywords())
                .defaultCrs(publication.getDefaultCrs())
                .supportedCrs(readStringList(publication.getSupportedCrsJson()))
                .tileMatrixSets(readStringList(publication.getTileMatrixSetsJson()))
                .boundsWgs84(readDoubleList(publication.getBoundsWgs84Json()))
                .styleId(publication.getStyleId())
                .styleName(publication.getStyleName())
                .capabilitiesUrls(capabilitiesUrls)
                .serviceUrls(serviceUrls)
                .tileUrlTemplates(tileUrlTemplates)
                .serviceUrl(serviceUrls.getOrDefault(primary, serviceUrls.values().stream().findFirst().orElse("")))
                .descriptorUrl(capabilitiesUrls.getOrDefault("WMS",
                        capabilitiesUrls.values().stream().findFirst().orElse(null)))
                .tileUrlTemplate(tileUrlTemplates.getOrDefault("WMTS",
                        tileUrlTemplates.values().stream().findFirst().orElse(null)))
                .tmsUrl(capabilitiesUrls.get("TMS"))
                .tmsTileUrlTemplate(tileUrlTemplates.get("TMS"))
                .tileSetId(publication.getTileSetId())
                .sourceTaskId(publication.getSourceTaskId())
                .taskName(taskName)
                .vectorResourceId(publication.getVectorResourceId() == null
                        ? null : publication.getVectorResourceId().toString())
                .vectorDefinition(publication.getVectorDefinition())
                .dataSetId(publication.getDataSetId())
                .dataSetName(dataSet == null ? null : dataSet.getDataSetName())
                .status(publication.getStatus())
                .outputKey(tileSet == null ? null : tileSet.getOutputKey())
                .outputFormat(publication.getOutputFormat())
                .targetCrs(publication.getTargetCrs())
                .tileProfile(publication.getTileProfile())
                .minZoom(publication.getMinZoom())
                .maxZoom(publication.getMaxZoom())
                .releaseVersion(releaseVersion(publication))
                .defaultStyleId(publication.getStyleId())
                .publishTime(publication.getPublishTime())
                .updateTime(publication.getUpdateTime())
                .build();
    }

    private GisProcessingType processingType(String value) {
        try {
            return GisProcessingType.valueOf(value);
        } catch (RuntimeException ex) {
            return GisProcessingType.IMAGERY;
        }
    }

    private GisProcessingType normalizeProcessingType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return GisProcessingType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("发布类型只能为 TERRAIN、IMAGERY 或 VECTOR", ex);
        }
    }

    private JsonNode readDescriptor(GisTileSet tileSet) throws IOException {
        Path root = storageService.resolveTiles(tileSet.getOutputKey());
        Path descriptor = root.resolve("tilejson.json");
        if (!Files.isRegularFile(descriptor)) {
            descriptor = root.resolve("manifest.json");
        }
        if (!Files.isRegularFile(descriptor)) {
            throw new IllegalStateException("切片产物描述文件缺失");
        }
        return objectMapper.readTree(descriptor.toFile());
    }

    private int descriptorMin(JsonNode descriptor, GisTileSet tileSet) {
        if (descriptor != null) {
            return descriptor.path("minzoom").asInt(
                    descriptor.path("minZoom").asInt(tileSet.getMinZoom() == null ? 0 : tileSet.getMinZoom()));
        }
        return tileSet == null || tileSet.getMinZoom() == null ? 0 : tileSet.getMinZoom();
    }

    private int descriptorMax(JsonNode descriptor, GisTileSet tileSet, GisVectorResource resource) {
        if (descriptor != null) {
            return descriptor.path("maxzoom").asInt(
                    descriptor.path("maxZoom").asInt(tileSet.getMaxZoom() == null ? 0 : tileSet.getMaxZoom()));
        }
        if (resource != null) {
            try {
                return objectMapper.readTree(resource.getDefinitionJson())
                        .path("map").path("nativeMaxZoom").asInt(18);
            } catch (RuntimeException ignored) {
                return 18;
            }
        }
        return tileSet == null || tileSet.getMaxZoom() == null ? 18 : tileSet.getMaxZoom();
    }

    private String targetCrs(JsonNode descriptor, GisTileSet tileSet) {
        if (descriptor != null) {
            String crs = descriptor.path("crs").asText(descriptor.path("targetCrs").asText(""));
            if (!crs.isBlank()) {
                return crs;
            }
        }
        return tileSet == null || tileSet.getTargetCrs() == null ? "EPSG:3857" : tileSet.getTargetCrs();
    }

    private List<Double> boundsOf(JsonNode descriptor, GisTileSet tileSet) {
        JsonNode bounds = descriptor == null ? null : descriptor.path("bounds");
        if (bounds == null || !bounds.isArray() || bounds.size() != 4) {
            return List.of(-180d, -85.05112877980659d, 180d, 85.05112877980659d);
        }
        List<Double> result = new ArrayList<>();
        for (JsonNode value : bounds) {
            result.add(value.asDouble());
        }
        return result;
    }

    private String buildVectorDefinition(Long dataSetId, UnifiedPublicationRequest request) {
        List<Long> ids = request.styleSchemeIds() == null || request.styleSchemeIds().isEmpty()
                ? List.of(request.defaultStyleSchemeId() == null ? 0L : request.defaultStyleSchemeId())
                : request.styleSchemeIds().stream().distinct().toList();
        if (ids.isEmpty() || ids.getFirst() <= 0) {
            List<GisVectorVO.Scheme> schemes = vectorStyles.schemes(dataSetId);
            if (schemes.isEmpty()) {
                throw new IllegalArgumentException("矢量数据集没有可用样式方案");
            }
            ids = List.of(Long.parseLong(schemes.getFirst().id()));
        }
        Long defaultId = request.defaultStyleSchemeId() == null
                ? ids.getFirst() : request.defaultStyleSchemeId();
        if (!ids.contains(defaultId)) {
            throw new IllegalArgumentException("默认样式方案必须属于所选方案");
        }
        ObjectNode binding = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("releaseVersion", UUID.randomUUID().toString())
                .put("defaultStyleId", defaultId.toString());
        ArrayNode styles = binding.putArray("styles");
        for (Long id : ids) {
            GisVectorVO.Scheme scheme = vectorStyles.scheme(dataSetId, id);
            styles.add(objectMapper.valueToTree(scheme));
        }
        return binding.toString();
    }

    private String styleName(Long dataSetId, Long styleId) {
        if (dataSetId == null || styleId == null) {
            return null;
        }
        try {
            return vectorStyles.scheme(dataSetId, styleId).name();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String releaseVersion(GisPublication publication) {
        if (publication.getVectorDefinition() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(publication.getVectorDefinition())
                    .path("releaseVersion").asText(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String serviceCode(GisProcessingType processingType) {
        String prefix = switch (processingType) {
            case IMAGERY -> "IMG_";
            case VECTOR -> "VEC_";
            case TERRAIN -> "TRN_";
        };
        return prefix + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private String primaryProtocol(GisProcessingType processingType, boolean dynamicVector) {
        if (dynamicVector) {
            return "WMS";
        }
        return processingType == GisProcessingType.TERRAIN ? "TERRAIN" : "TMS_WMTS";
    }

    private void buildProtocolUrls(String code, GisProcessingType processingType,
            Set<OgcProtocol> protocols, Map<String, String> serviceUrls,
            Map<String, String> capabilitiesUrls, Map<String, String> tileUrlTemplates) {
        for (OgcProtocol protocol : protocols) {
            switch (protocol) {
                case WMS -> {
                    serviceUrls.put("WMS", recordService.buildPublicUrl("/gis/wms"));
                    capabilitiesUrls.put("WMS", recordService.buildPublicUrl(
                            "/gis/wms?SERVICE=WMS&REQUEST=GetCapabilities"));
                }
                case WMTS -> {
                    serviceUrls.put("WMTS", recordService.buildPublicUrl("/gis/wmts"));
                    capabilitiesUrls.put("WMTS", recordService.buildPublicUrl(
                            "/gis/wmts?SERVICE=WMTS&REQUEST=GetCapabilities"));
                    tileUrlTemplates.put("WMTS", recordService.buildPublicUrl("/gis/wmts/rest/" + code
                            + "/{Style}/WebMercatorQuad/{TileMatrix}/{TileRow}/{TileCol}.png"));
                }
                case TMS -> {
                    serviceUrls.put("TMS", recordService.buildPublicUrl("/gis/tms/1.0.0"));
                    capabilitiesUrls.put("TMS", recordService.buildPublicUrl("/gis/tms/1.0.0"));
                    tileUrlTemplates.put("TMS", recordService.buildPublicUrl("/gis/tms/1.0.0/" + code
                            + "@WebMercatorQuad@png@" + (processingType == GisProcessingType.VECTOR ? "vector/" : "")
                            + "{z}/{x}/{y}.png"));
                }
                case WFS -> {
                    serviceUrls.put("WFS", recordService.buildPublicUrl("/gis/wfs"));
                    capabilitiesUrls.put("WFS", recordService.buildPublicUrl(
                            "/gis/wfs?SERVICE=WFS&REQUEST=GetCapabilities"));
                }
                case XYZ -> tileUrlTemplates.put("XYZ", recordService.buildPublicUrl(
                        "/gis/publications/" + processingType.name() + "/" + code
                                + "/{z}/{x}/{y}.png"));
            }
        }
    }

    private Long commonDataSetId(Long taskId) {
        Long result = null;
        for (GisProcessingInput input : inputMapper.selectByTaskId(taskId)) {
            Long current = input.getSourceDataSetId();
            if (current == null) {
                return null;
            }
            if (result == null) {
                result = current;
            } else if (!result.equals(current)) {
                return null;
            }
        }
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("发布元数据序列化失败", ex);
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<String> result = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(value)) {
                result.add(item.asText());
            }
            return List.copyOf(result);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<Double> readDoubleList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<Double> result = new ArrayList<>();
            for (JsonNode item : objectMapper.readTree(value)) {
                result.add(item.asDouble());
            }
            return List.copyOf(result);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Map<String, String> readStringMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> result = new LinkedHashMap<>();
            JsonNode node = objectMapper.readTree(value);
            for (String field : node.propertyNames()) {
                result.put(field, node.path(field).asText());
            }
            return Map.copyOf(result);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }
}
