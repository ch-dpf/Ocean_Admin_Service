package org.ocean.admin.gis.service;

import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GIS 服务发布、查询、停用和公开资源访问的统一业务门面。 */
@Service
public class PublicationService {

    private final PublicationRecordService recordService;
    private final ProcessingTaskRecordService taskService;
    private final Map<GisProcessingType, PublicationProtocol> protocols;

    public PublicationService(
            PublicationRecordService recordService,
            ProcessingTaskRecordService taskService,
            List<PublicationProtocol> protocolList) {
        this.recordService = recordService;
        this.taskService = taskService;
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

    public GisPublicationVO publish(GisProcessingType processingType, Long sourceTaskId) {
        PublicationProtocol protocol = requireProtocol(processingType);
        GisProcessingTask task = taskService.getRequired(sourceTaskId);
        GisTileSet tileSet = recordService.getRequiredTileSet(sourceTaskId, processingType);
        PublicationProtocol.PublishedArtifact artifact =
                protocol.validateForPublish(task, tileSet);
        GisPublication existing = recordService.findBySourceTaskId(
                processingType, sourceTaskId);
        String serviceCode = existing == null
                ? protocol.generateServiceCode()
                : existing.getServiceCode();
        GisPublication publication = recordService.publish(
                tileSet, serviceCode, protocol.displayName());
        protocol.activate(publication.getServiceCode(), artifact);
        return toVO(publication);
    }

    public GisPublicationVO get(GisProcessingType processingType, String serviceCode) {
        PublicationProtocol protocol = requireProtocol(processingType);
        return toVO(recordService.getRequired(
                processingType, protocol.normalizeServiceCode(serviceCode)));
    }

    public GisPublicationVO disable(GisProcessingType processingType, String serviceCode) {
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
        GisTileSet tileSet = recordService.getPublicationTileSet(publication);
        PublicationProtocol protocol = protocols.get(tileSet.getTileType());
        PublicationProtocol.PublicationAccess access = protocol == null
                ? new PublicationProtocol.PublicationAccess(
                        recordService.buildServiceRoot(
                                tileSet.getTileType(), publication.getServiceCode()),
                        null, null)
                : protocol.buildAccess(publication, tileSet);
        return GisPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .processingType(tileSet.getTileType())
                .tileSetId(tileSet.getId())
                .sourceTaskId(tileSet.getTaskId())
                .dataSetId(publication.getDataSetId())
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
                .updateTime(publication.getUpdateTime())
                .build();
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
