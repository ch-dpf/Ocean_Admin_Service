package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private final VectorStyleSchemeService vectorStyles;
    private final ObjectMapper objectMapper;

    public PageResult<List<GisPublicationVO>> getPublicationPage(
            Integer current, Integer size, String processingType, String serviceCode,
            String status, Long dataSetId, Long sourceTaskId,
            LocalDateTime publishTimeStart, LocalDateTime publishTimeEnd) {
        PageResult<List<GisPublication>> page = recordService.getPublicationPage(
                normalizeProcessingType(processingType), current, size, serviceCode,
                status, dataSetId, sourceTaskId, publishTimeStart, publishTimeEnd);
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
        Set<OgcProtocol> requested = OgcProtocol.validate(processingType.name(), request.protocols());
        GisPublication existing = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getSourceTaskId, task.getId()));
        if (existing != null) {
            Set<OgcProtocol> already = new LinkedHashSet<>();
            for (String protocol : readStringList(existing.getProtocolsJson())) {
                try {
                    already.add(OgcProtocol.parse(protocol));
                } catch (RuntimeException ignored) {
                    // 旧记录中的未知协议忽略，不影响新协议追加
                }
            }
            Set<OgcProtocol> added = new LinkedHashSet<>(requested);
            added.removeAll(already);
            if (added.isEmpty()) {
                throw new IllegalArgumentException("所选协议均已发布，不要再重复发布");
            }
            already.addAll(added);
            requested = already;
        }
        Set<OgcProtocol> protocols = requested;
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
            vectorDefinition = vectorStyles.publicationDefinition(dataSetId, request.styleSchemeIds(), request.defaultStyleSchemeId());
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

        String serviceCode = existing == null ? serviceCode(processingType) : existing.getServiceCode();
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

    /** 仅移除已停用的发布记录，保留来源数据和处理成果供再次发布。 */
    @Transactional(rollbackFor = Exception.class)
    public void remove(GisProcessingType processingType, String serviceCode) {
        GisPublication publication = recordService.getRequired(processingType, serviceCode);
        if (publication.getSourceTaskId() != null) {
            // 与发布、任务删除使用同一把任务锁，避免移除时并发恢复发布或清理成果。
            taskService.getRequiredForUpdate(publication.getSourceTaskId());
        }
        if (!DISABLED.equals(publication.getStatus())) {
            throw new IllegalStateException("只有已停用的服务可以移除，请先停用服务");
        }
        // 删除时再次校验状态，防止等待任务锁期间服务已经重新发布。
        if (publicationMapper.deleteDisabledPermanently(publication.getId()) != 1) {
            throw new IllegalStateException("服务状态已变化，请刷新列表后重试");
        }
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
                .styles(publication.getVectorDefinition() == null ? null
                        : objectMapper.readTree(publication.getVectorDefinition()).path("styles"))
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
                            + "@WebMercatorQuad@png" + (processingType == GisProcessingType.VECTOR ? "@vector" : "")
                            + "/{z}/{x}/{y}.png"));
                }
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
