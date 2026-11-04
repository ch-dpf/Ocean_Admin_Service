package org.ocean.admin.gis.service;

import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.ogc.PublishedImageryCatalog;
import org.ocean.admin.gis.ogc.TileMatrixSetDefinition;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GIS 服务发布、查询、停用和公开资源访问的统一业务门面。 */
@Service
public class PublicationService {

    private final PublicationRecordService recordService;
    private final ProcessingTaskRecordService taskService;
    private final PublishedImageryCatalog imageryCatalog;
    private final GisDataSetMapper dataSetMapper;
    private final VectorPublicationService vectors;
    private final Map<GisProcessingType, PublicationProtocol> protocols;

    public PublicationService(
            PublicationRecordService recordService,
            ProcessingTaskRecordService taskService,
            PublishedImageryCatalog imageryCatalog,
            List<PublicationProtocol> protocolList,
            GisDataSetMapper dataSetMapper, VectorPublicationService vectors) {
        this.recordService = recordService;
        this.taskService = taskService;
        this.imageryCatalog = imageryCatalog;
        this.dataSetMapper = dataSetMapper;
        this.vectors = vectors;
        EnumMap<GisProcessingType, PublicationProtocol> protocolMap =
                new EnumMap<>(GisProcessingType.class);
        for (PublicationProtocol protocol : protocolList) {
            if (protocolMap.put(protocol.type(), protocol) != null) {
                throw new IllegalStateException("发布协议重复注册: " + protocol.type());
            }
        }
        requireRegisteredProtocol(protocolMap, GisProcessingType.IMAGERY);
        requireRegisteredProtocol(protocolMap, GisProcessingType.TERRAIN);
        this.protocols = Map.copyOf(protocolMap);
    }

    public PageResult<List<GisPublicationVO>> getPublicationPage(
            Integer current, Integer size, String processingType, String serviceCode,
            String status, Long dataSetId, LocalDateTime publishTimeStart,
            LocalDateTime publishTimeEnd) {
        GisProcessingType type = normalizeProcessingType(processingType);
        PageResult<List<GisPublication>> page = recordService.getPublicationPage(
                type, current, size, serviceCode, status, dataSetId,
                publishTimeStart, publishTimeEnd);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::toVO).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublicationVO publish(GisProcessingType processingType, Long sourceTaskId, String serviceName) {
        PublicationProtocol protocol = requireProtocol(processingType);
        GisProcessingTask task = taskService.getRequiredForUpdate(sourceTaskId);
        GisTileSet tileSet = recordService.getRequiredTileSet(sourceTaskId, processingType);
        PublicationProtocol.PublishedArtifact artifact =
                protocol.validateForPublish(task, tileSet);
        GisPublication existing = recordService.findBySourceTaskId(
                processingType, sourceTaskId);
        String serviceCode = existing == null
                ? protocol.generateServiceCode()
                : existing.getServiceCode();
        GisPublication publication = recordService.publish(
                tileSet, serviceCode, protocol.displayName(), serviceName);
        protocol.activate(publication.getServiceCode(), artifact);
        return toVO(publication);
    }

    public GisPublicationVO get(GisProcessingType processingType, String serviceCode) {
        if(processingType==GisProcessingType.VECTOR) return vectors.toVO(vectors.get(serviceCode,false));
        PublicationProtocol protocol = requireProtocol(processingType);
        return toVO(recordService.getRequired(
                processingType, protocol.normalizeServiceCode(serviceCode)));
    }

    public GisPublicationVO rename(GisProcessingType processingType, String serviceCode, String serviceName) {
        if(processingType==GisProcessingType.VECTOR) return vectors.rename(serviceCode,serviceName);
        PublicationProtocol protocol = requireProtocol(processingType);
        return toVO(recordService.rename(processingType,
                protocol.normalizeServiceCode(serviceCode), serviceName));
    }

    public GisPublicationVO disable(GisProcessingType processingType, String serviceCode) {
        if(processingType==GisProcessingType.VECTOR) return vectors.disable(serviceCode);
        PublicationProtocol protocol = requireProtocol(processingType);
        String normalizedCode = protocol.normalizeServiceCode(serviceCode);
        GisPublication publication = recordService.disable(
                processingType, normalizedCode, protocol.displayName());
        protocol.deactivate(normalizedCode);
        return toVO(publication);
    }

    public Path resolvePublishedFile(
            GisProcessingType processingType, String serviceCode, String relativePath) {
        PublicationProtocol protocol = requireProtocol(processingType);
        return protocol.resolvePublishedFile(
                protocol.normalizeServiceCode(serviceCode), relativePath);
    }

    private GisPublicationVO toVO(GisPublication publication) {
        if(publication.getVectorResourceId()!=null) return vectors.toVO(publication);
        GisTileSet tileSet = recordService.getPublicationTileSet(publication);
        GisDataSet dataSet = null;
        if (publication.getDataSetId() != null) {
            dataSet = dataSetMapper.selectById(publication.getDataSetId());
        }
        PublicationProtocol protocol = protocols.get(tileSet.getTileType());
        PublicationProtocol.PublicationAccess access = protocol == null
                ? new PublicationProtocol.PublicationAccess(
                        recordService.buildServiceRoot(
                                tileSet.getTileType(), publication.getServiceCode()),
                        null, null)
                : protocol.buildAccess(publication, tileSet);
        GisPublicationVO.GisPublicationVOBuilder builder = GisPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .serviceName(publication.getServiceName())
                .processingType(tileSet.getTileType())
                .tileSetId(tileSet.getId())
                .sourceTaskId(tileSet.getTaskId())
                .taskName(taskService.getRequired(tileSet.getTaskId()).getTaskName())
                .dataSetId(publication.getDataSetId())
                .dataSetName(dataSet == null ? null : dataSet.getDataSetName())
                .status(publication.getStatus())
                .outputKey(tileSet.getOutputKey())
                .targetCrs(tileSet.getTargetCrs())
                .tileProfile(tileSet.getTileProfile())
                .outputFormat(tileSet.getOutputFormat())
                .minZoom(tileSet.getMinZoom())
                .maxZoom(tileSet.getMaxZoom())
                .serviceUrl(access.serviceUrl())
                .descriptorUrl(access.descriptorUrl())
                .tileUrlTemplate(access.tileUrlTemplate())
                .publishTime(publication.getPublishTime())
                .updateTime(publication.getUpdateTime());
        if (tileSet.getTileType() == GisProcessingType.IMAGERY) {
            appendWmtsAccess(builder, publication, tileSet);
        }
        return builder.build();
    }

    /** 补齐前端直接调用 WMTS 所需参数；瓦片集未就绪时只返回能力文档与矩阵集。 */
    private void appendWmtsAccess(GisPublicationVO.GisPublicationVOBuilder builder,
            GisPublication publication, GisTileSet tileSet) {
        String serviceCode = publication.getServiceCode();
        builder.wmtsCapabilitiesUrl(recordService.buildPublicUrl(
                        "/gis/wmts?SERVICE=WMTS&REQUEST=GetCapabilities"))
                .wmtsLayer(serviceCode);
        TileMatrixSetDefinition matrixSet =
                TileMatrixSetDefinition.resolveByCrs(tileSet.getTargetCrs());
        if (matrixSet == null) {
            return;
        }
        String extension = "JPEG".equalsIgnoreCase(tileSet.getOutputFormat()) ? "jpg" : "png";
        builder.wmtsTileMatrixSet(matrixSet.id())
                .wmtsTileUrlTemplate(recordService.buildPublicUrl(
                        "/gis/wmts/rest/" + serviceCode + "/default/" + matrixSet.id()
                                + "/{TileMatrix}/{TileRow}/{TileCol}." + extension));
        PublishedImageryCatalog.PublishedLayer layer =
                imageryCatalog.load(tileSet, serviceCode);
        if (layer != null) {
            builder.wmtsTileMatrixSetLimits(matrixLimits(layer));
        }
    }

    /** 只为实际存在瓦片的层级输出范围，与能力文档中的 TileMatrixSetLimits 保持一致。 */
    private List<GisPublicationVO.WmtsMatrixLimitsVO> matrixLimits(
            PublishedImageryCatalog.PublishedLayer layer) {
        List<GisPublicationVO.WmtsMatrixLimitsVO> limits = new ArrayList<>();
        for (int zoom = layer.minZoom(); zoom <= layer.maxZoom(); zoom++) {
            if (layer.matrixSet().matrixWidth(zoom) < 2
                    || layer.matrixSet().matrixHeight(zoom) < 2) {
                continue;
            }
            TileMatrixSetDefinition.MatrixLimits range =
                    layer.matrixSet().limits(zoom, layer.wgs84Bounds());
            limits.add(GisPublicationVO.WmtsMatrixLimitsVO.builder()
                    .zoom(zoom)
                    .minRow(range.minRow())
                    .maxRow(range.maxRow())
                    .minCol(range.minCol())
                    .maxCol(range.maxCol())
                    .build());
        }
        return limits;
    }

    private PublicationProtocol requireProtocol(GisProcessingType processingType) {
        if (processingType == null) {
            throw new IllegalArgumentException("发布类型不能为空");
        }
        PublicationProtocol protocol = protocols.get(processingType);
        if (protocol == null) {
            throw new IllegalArgumentException("暂不支持该发布类型: " + processingType);
        }
        return protocol;
    }

    private GisProcessingType normalizeProcessingType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return GisProcessingType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "发布类型只能为 TERRAIN、IMAGERY 或 VECTOR", ex);
        }
    }

    private static void requireRegisteredProtocol(
            Map<GisProcessingType, PublicationProtocol> protocols,
            GisProcessingType processingType) {
        if (!protocols.containsKey(processingType)) {
            throw new IllegalStateException("缺少发布协议: " + processingType);
        }
    }
}
