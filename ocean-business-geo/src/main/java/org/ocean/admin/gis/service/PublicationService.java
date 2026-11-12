package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.ocean.admin.gis.dto.UnifiedPublicationRequest;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.ogc.OgcProtocol;
import org.ocean.admin.gis.ogc.PublicationCatalog;
import org.ocean.admin.gis.ogc.PublicationLayer;
import org.ocean.admin.gis.ogc.TileMatrixSetDefinition;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 按已完成任务发布单协议 GIS 服务；一条记录只对应一个服务与一份能力文档。 */
@Service
public class PublicationService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final String READY = "READY";

    private final PublicationRecordService recordService;
    private final ProcessingTaskRecordService taskService;
    private final GisDataSetMapper dataSetMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisPublicationMapper publicationMapper;
    private final PublicationCatalog catalog;
    private final ObjectMapper objectMapper;

    public PublicationService(PublicationRecordService recordService,
            ProcessingTaskRecordService taskService,
            GisDataSetMapper dataSetMapper,
            GisProcessingInputMapper inputMapper,
            GisTileSetMapper tileSetMapper,
            GisPublicationMapper publicationMapper,
            PublicationCatalog catalog,
            ObjectMapper objectMapper) {
        this.recordService = recordService;
        this.taskService = taskService;
        this.dataSetMapper = dataSetMapper;
        this.inputMapper = inputMapper;
        this.tileSetMapper = tileSetMapper;
        this.publicationMapper = publicationMapper;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

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

    /** 按任务产物发布一条单协议记录；同一任务同一协议重发覆盖原记录，换协议则生成独立新记录。 */
    @Transactional(rollbackFor = Exception.class)
    public GisPublicationVO publish(GisProcessingType processingType, Long sourceTaskId,
            UnifiedPublicationRequest request) {
        GisProcessingTask task = taskService.getRequiredForUpdate(sourceTaskId);
        if (task.getProcessingType() != processingType) {
            throw new IllegalArgumentException("发布类型与任务处理类型不一致");
        }
        if (!"COMPLETED".equals(task.getTaskStatus().name())) {
            throw new IllegalStateException("只有 COMPLETED 状态的任务可以发布");
        }
        OgcProtocol protocol = OgcProtocol.parsePublishable(request.protocol());
        // 协议与任务类型必须配对：量化地形只有专用 TERRAIN 协议，
        // 影像与矢量瓦片走 OGC 协议，不允许互串。
        if (protocol == OgcProtocol.TERRAIN && processingType != GisProcessingType.TERRAIN) {
            throw new IllegalArgumentException("TERRAIN 协议仅用于地形任务发布");
        }
        if (protocol != OgcProtocol.TERRAIN && processingType == GisProcessingType.TERRAIN) {
            throw new IllegalArgumentException("地形任务仅支持 TERRAIN 协议发布");
        }
        // 仅匹配同任务同协议的记录，保证各协议独立成条互不覆盖。
        GisPublication existing = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getSourceTaskId, task.getId())
                        .eq(GisPublication::getServiceProtocol, protocol.name()));

        Long dataSetId = commonDataSetId(task.getId());
        GisTileSet tileSet = tileSetMapper.selectByTaskId(task.getId());
        if (tileSet == null || tileSet.getTileType() != processingType
                || !READY.equals(tileSet.getTileSetStatus())) {
            throw new IllegalStateException("任务没有对应类型且已就绪的瓦片集");
        }
        PublicationLayer.TileArtifact artifact = catalog.readTileArtifact(tileSet);
        int minZoom = request.minZoom() == null ? artifact.minZoom() : request.minZoom();
        int maxZoom = request.maxZoom() == null ? artifact.maxZoom() : request.maxZoom();
        if (minZoom < 0 || maxZoom < minZoom || maxZoom > TileMatrixSetDefinition.MAX_ZOOM) {
            throw new IllegalArgumentException("服务层级范围不合法");
        }
        // 地形服务的层级必须与切片产物 layer.json 的 available 完全一致，
        // 否则客户端按元数据请求不到瓦片，因此不接受自定义覆盖。
        if (protocol == OgcProtocol.TERRAIN) {
            minZoom = artifact.minZoom();
            maxZoom = artifact.maxZoom();
        }
        String spec = serviceCode(tileSet, existing);
        String matrixSetId = artifact.matrixSet().id();
        String extension = artifact.extension();
        String tileMapSpec = spec + "@" + matrixSetId + "@" + extension;
        String serviceRoot;
        if (protocol == OgcProtocol.WMTS) {
            serviceRoot = recordService.buildPublicUrl("/gis/wmts/" + spec);
        } else if (protocol == OgcProtocol.TMS) {
            serviceRoot = recordService.buildPublicUrl("/gis/tms/1.0.0/" + spec);
        } else {
            // TERRAIN 为量化地形专用入口，CesiumTerrainProvider 以该根地址初始化。
            serviceRoot = recordService.buildPublicUrl("/gis/terrain/" + spec);
        }

        Map<String, String> serviceUrls = new LinkedHashMap<>();
        Map<String, String> capabilitiesUrls = new LinkedHashMap<>();
        Map<String, String> tileUrlTemplates = new LinkedHashMap<>();
        serviceUrls.put(protocol.name(), serviceRoot);
        if (protocol == OgcProtocol.WMTS) {
            capabilitiesUrls.put(protocol.name(), serviceRoot
                    + "?SERVICE=WMTS&REQUEST=GetCapabilities");
            tileUrlTemplates.put(protocol.name(), serviceRoot + "/rest/" + spec
                    + "/default/" + matrixSetId
                    + "/{TileMatrix}/{TileRow}/{TileCol}." + extension);
        } else if (protocol == OgcProtocol.TMS) {
            capabilitiesUrls.put(protocol.name(), serviceRoot);
            tileUrlTemplates.put(protocol.name(), serviceRoot + "/" + tileMapSpec
                    + "/{z}/{x}/{y}." + extension);
        } else {
            // TERRAIN 的能力地址即量化地形元数据 layer.json，客户端直接以此为入口。
            capabilitiesUrls.put(protocol.name(), serviceRoot + "/layer.json");
            tileUrlTemplates.put(protocol.name(), serviceRoot
                    + "/{z}/{x}/{y}." + extension);
        }

        LocalDateTime now = LocalDateTime.now();
        GisPublication publication = new GisPublication();
        publication.setServiceCode(spec);
        publication.setServiceName(request.serviceName());
        publication.setServiceProtocol(protocol.name());
        publication.setSourceTaskId(task.getId());
        publication.setProcessingType(processingType.name());
        publication.setMinZoom(minZoom);
        publication.setMaxZoom(maxZoom);
        publication.setTargetCrs(artifact.targetCrs());
        publication.setTileMatrixSet(matrixSetId);
        publication.setBoundsWgs84Json(writeJson(artifact.wgs84Bounds()));
        publication.setStyleId(null);
        publication.setStyleName(null);
        publication.setPublicBaseUrl(recordService.publicBaseUrl());
        publication.setCapabilitiesUrlsJson(writeJson(capabilitiesUrls));
        publication.setServiceUrlsJson(writeJson(serviceUrls));
        publication.setTileUrlTemplatesJson(writeJson(tileUrlTemplates));
        publication.setTileSetId(tileSet.getId());
        publication.setVectorResourceId(null);
        publication.setVectorDefinition(null);
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
        if (!PublicationRecordService.DISABLED.equals(publication.getStatus())) {
            throw new IllegalStateException("只有已停用的服务可以移除，请先停用服务");
        }
        // 删除时再次校验状态，防止等待任务锁期间服务已经重新发布。
        if (publicationMapper.deleteDisabledPermanently(publication.getId()) != 1) {
            throw new IllegalStateException("服务状态已变化，请刷新列表后重试");
        }
    }

    /** 统一发布视图：JSON 文本列反序列化，单值地址字段从单协议 URL 映射派生。 */
    private GisPublicationVO toVO(GisPublication publication) {
        String protocol = publication.getServiceProtocol();
        Map<String, String> capabilitiesUrls = readStringMap(publication.getCapabilitiesUrlsJson());
        Map<String, String> serviceUrls = readStringMap(publication.getServiceUrlsJson());
        Map<String, String> tileUrlTemplates = readStringMap(publication.getTileUrlTemplatesJson());
        List<Double> bounds = readDoubleList(publication.getBoundsWgs84Json());
        TileMatrixSetDefinition matrixSet = TileMatrixSetDefinition.resolve(publication.getTileMatrixSet());
        if (matrixSet != null && bounds.size() == 4) {
            double[] normalized = matrixSet.normalizeWgs84Bounds(new double[]{
                    bounds.get(0), bounds.get(1), bounds.get(2), bounds.get(3)});
            bounds = List.of(normalized[0], normalized[1], normalized[2], normalized[3]);
        }
        GisTileSet tileSet = publication.getTileSetId() == null ? null
                : tileSetMapper.selectById(publication.getTileSetId());
        GisDataSet dataSet = publication.getDataSetId() == null ? null
                : dataSetMapper.selectById(publication.getDataSetId());
        String taskName = null;
        if (publication.getSourceTaskId() != null) {
            GisProcessingTask task = taskService.getRequired(publication.getSourceTaskId());
            taskName = task.getTaskName();
        }
        return GisPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .serviceName(publication.getServiceName())
                .serviceProtocol(protocol)
                .protocols(protocol == null ? List.of() : List.of(protocol))
                .processingType(processingType(publication.getProcessingType()))
                .boundsWgs84(bounds)
                .styleId(publication.getStyleId())
                .styleName(publication.getStyleName())
                .capabilitiesUrls(capabilitiesUrls)
                .serviceUrls(serviceUrls)
                .tileUrlTemplates(tileUrlTemplates)
                .serviceUrl(serviceUrls.getOrDefault(protocol,
                        serviceUrls.values().stream().findFirst().orElse("")))
                .descriptorUrl(capabilitiesUrls.getOrDefault(protocol,
                        capabilitiesUrls.values().stream().findFirst().orElse(null)))
                .tileUrlTemplate(tileUrlTemplates.getOrDefault(protocol,
                        tileUrlTemplates.values().stream().findFirst().orElse(null)))
                .tmsUrl(capabilitiesUrls.get("TMS"))
                .tmsTileUrlTemplate(tileUrlTemplates.get("TMS"))
                .tileSetId(publication.getTileSetId())
                .sourceTaskId(publication.getSourceTaskId())
                .taskName(taskName)
                .dataSetId(publication.getDataSetId())
                .dataSetName(dataSet == null ? null : dataSet.getDataSetName())
                .status(publication.getStatus())
                .outputKey(tileSet == null ? null : tileSet.getOutputKey())
                .targetCrs(publication.getTargetCrs())
                .tileMatrixSet(publication.getTileMatrixSet())
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

    /** 同任务同协议重发时保留原编码；新协议或首次发布按类型生成唯一编码。 */
    private String serviceCode(GisTileSet tileSet, GisPublication existing) {
        if (existing != null) {
            return existing.getServiceCode();
        }
        String prefix = switch (tileSet.getTileType()) {
            case IMAGERY -> "IMG_";
            case VECTOR -> "VEC_";
            case TERRAIN -> "TRN_";
        };
        return prefix + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
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

    private List<Double> readDoubleList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<Double> result = new ArrayList<>();
            for (var item : objectMapper.readTree(value)) {
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
            var node = objectMapper.readTree(value);
            for (String field : node.propertyNames()) {
                result.put(field, node.path(field).asText());
            }
            return Map.copyOf(result);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }
}
