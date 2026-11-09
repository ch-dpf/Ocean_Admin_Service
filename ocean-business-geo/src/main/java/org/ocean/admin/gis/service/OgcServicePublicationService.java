package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.dto.OgcServicePublishRequest;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisOgcService;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisOgcServiceMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.ogc.OgcProtocol;
import org.ocean.admin.gis.ogc.TileMatrixSetDefinition;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisOgcServiceVO;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/** WMTS、WMS、TMS 栅格独立服务与动态矢量 OGC 服务的统一发布入口。 */
@Service
@RequiredArgsConstructor
public class OgcServicePublicationService {

    public static final String PUBLISHED = "PUBLISHED";
    public static final String DISABLED = "DISABLED";

    private static final String RASTER = "RASTER";
    private static final String VECTOR = "VECTOR";
    private static final String AVAILABLE = "AVAILABLE";
    private static final String READY = "READY";
    private static final String IMAGERY_PREFIX = "imagery/";

    private final GisOgcServiceMapper serviceMapper;
    private final GisTileSetMapper tileSetMapper;
    private final ProcessingTaskRecordService taskService;
    private final PublicationRecordService recordService;
    private final GisProcessingStorageService storageService;
    private final GisProcessingInputMapper inputMapper;
    private final GisDataSetMapper dataSetMapper;
    private final VectorPublicationService vectorPublicationService;
    private final ObjectMapper objectMapper;

    /** 分页查询独立服务列表；行内包含地址、能力参数、状态与关联信息。 */
    public PageResult<List<GisOgcServiceVO>> page(Integer current, Integer size,
            String protocol, String status, String keyword, Long dataSetId,
            LocalDateTime publishTimeStart, LocalDateTime publishTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        OgcProtocol normalizedProtocol = parseNullableProtocol(protocol);
        String normalizedStatus = upperTrimToNull(status);
        if (normalizedStatus != null
                && !PUBLISHED.equals(normalizedStatus) && !DISABLED.equals(normalizedStatus)) {
            throw new IllegalArgumentException("发布状态只能为 PUBLISHED 或 DISABLED");
        }
        if (publishTimeStart != null && publishTimeEnd != null
                && publishTimeStart.isAfter(publishTimeEnd)) {
            throw new IllegalArgumentException("发布开始时间不能晚于发布结束时间");
        }
        String normalizedKeyword = trimToNull(keyword);
        LambdaQueryWrapper<GisOgcService> query = new LambdaQueryWrapper<GisOgcService>()
                .eq(normalizedProtocol != null, GisOgcService::getProtocol, normalizedProtocol)
                .eq(normalizedStatus != null, GisOgcService::getStatus, normalizedStatus)
                .eq(dataSetId != null, GisOgcService::getDataSetId, dataSetId)
                .ge(publishTimeStart != null, GisOgcService::getPublishTime, publishTimeStart)
                .le(publishTimeEnd != null, GisOgcService::getPublishTime, publishTimeEnd)
                .and(normalizedKeyword != null, wrapper -> wrapper
                        .like(GisOgcService::getServiceCode, normalizedKeyword)
                        .or().like(GisOgcService::getServiceName, normalizedKeyword)
                        .or().like(GisOgcService::getLayerName, normalizedKeyword))
                .orderByDesc(GisOgcService::getPublishTime);
        Page<GisOgcService> page = serviceMapper.selectPage(
                new Page<>(currentPage, pageSize), query);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::toVO).toList());
    }

    /** 查询单个独立服务详情。 */
    public GisOgcServiceVO get(String serviceCode) {
        return toVO(required(serviceCode));
    }

    /**
     * 按资源类型发布 OGC 服务：栅格写独立服务记录，矢量复用动态矢量发布。
     *
     * <p>栅格发布前强校验任务完成、瓦片集 READY、tilejson.json 合法且至少存在一张瓦片；
     * 矢量发布写 gis_publication，运行时目录会自动把它纳入 WMS、WMTS、TMS 与 WFS。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public GisOgcServiceVO publish(OgcServicePublishRequest request) throws IOException {
        String resourceType = upperTrimToNull(request.resourceType());
        if (RASTER.equals(resourceType)) {
            return publishRaster(request);
        }
        if (VECTOR.equals(resourceType)) {
            return publishVector(request);
        }
        throw new IllegalArgumentException("发布资源类型只能为 RASTER 或 VECTOR");
    }

    /** 栅格模式校验条件必填字段，并沿用原有独立 OGC 发布流程。 */
    private GisOgcServiceVO publishRaster(OgcServicePublishRequest request) {
        if (request.sourceTaskId() == null) {
            throw new IllegalArgumentException("源任务 ID 不能为空");
        }
        if (request.protocol() == null || request.protocol().isBlank()) {
            throw new IllegalArgumentException("服务类型不能为空");
        }
        if (request.layerName() == null || request.layerName().isBlank()
                || request.layerName().length() > 200) {
            throw new IllegalArgumentException("图层名称不能为空且最多 200 个字符");
        }
        OgcProtocol protocol = OgcProtocol.parse(request.protocol());
        String serviceName = requiredText(request.serviceName(), "服务名称", 200);
        String layerName = requiredText(request.layerName(), "图层名称", 200);
        String description = optionalText(request.description(), "服务说明", 500);

        GisProcessingTask task = taskService.getRequiredForUpdate(request.sourceTaskId());
        GisTileSet tileSet = recordService.getRequiredTileSet(
                task.getId(), GisProcessingType.IMAGERY);
        if (task.getTaskStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException("只有 COMPLETED 状态的影像切片任务可以发布");
        }
        if (!READY.equals(tileSet.getTileSetStatus())
                || tileSet.getOutputKey() == null
                || !tileSet.getOutputKey().startsWith(IMAGERY_PREFIX)) {
            throw new IllegalStateException("影像瓦片集未就绪或产物 Key 不合法");
        }
        TileArtifact artifact = inspectArtifact(tileSet, true);
        int minZoom = request.minZoom() == null ? artifact.minZoom() : request.minZoom();
        int maxZoom = request.maxZoom() == null ? artifact.maxZoom() : request.maxZoom();
        if (minZoom < artifact.minZoom() || maxZoom > artifact.maxZoom() || minZoom > maxZoom) {
            throw new IllegalArgumentException("服务层级必须在切片产物范围内且最小层级不大于最大层级");
        }

        if (serviceMapper.countByTaskAndProtocol(task.getId(), protocol.name()) > 0) {
            throw new IllegalStateException(
                    "该任务的 " + protocol + " 服务记录已存在（含已停用记录），请使用启用接口恢复");
        }
        LocalDateTime now = LocalDateTime.now();
        GisOgcService service = new GisOgcService();
        service.setServiceCode(generateServiceCode(protocol));
        service.setProtocol(protocol);
        service.setServiceName(serviceName);
        service.setLayerName(layerName);
        service.setDescription(description);
        service.setTileSetId(tileSet.getId());
        service.setSourceTaskId(task.getId());
        service.setDataSetId(commonDataSetId(task.getId()));
        service.setStatus(PUBLISHED);
        service.setMinZoom(minZoom);
        service.setMaxZoom(maxZoom);
        service.setTargetCrs(tileSet.getTargetCrs());
        service.setTileMatrixSet(artifact.matrixSet().id());
        service.setOutputFormat(tileSet.getOutputFormat());
        service.setTileProfile(tileSet.getTileProfile());
        service.setPublishTime(now);
        service.setUpdateTime(now);
        if (serviceMapper.insert(service) != 1) {
            throw new IllegalStateException("OGC 服务发布记录保存失败");
        }
        return toVO(service, tileSet, artifact);
    }

    /** 矢量模式委托现有动态发布，返回统一 OGC 视图而不写 gis_ogc_service。 */
    private GisOgcServiceVO publishVector(OgcServicePublishRequest request) throws IOException {
        if (request.dataSetId() == null || request.dataSetId() <= 0) {
            throw new IllegalArgumentException("矢量数据集 ID 不能为空且必须为正数");
        }
        if (request.styleSchemeIds() == null || request.styleSchemeIds().isEmpty()) {
            throw new IllegalArgumentException("至少需要选择一套矢量样式方案");
        }
        if (request.styleSchemeIds().size() > 100) {
            throw new IllegalArgumentException("矢量样式方案最多 100 套");
        }
        if (request.defaultStyleSchemeId() == null || request.defaultStyleSchemeId() <= 0) {
            throw new IllegalArgumentException("默认样式方案 ID 不能为空且必须为正数");
        }
        GisVectorRequests.Publish vectorRequest = request.vectorRequest();
        if (!vectorRequest.styleSchemeIds().contains(vectorRequest.defaultStyleSchemeId())) {
            throw new IllegalArgumentException("默认样式方案必须属于所选方案");
        }
        return vectorVO(vectorPublicationService.publish(vectorRequest));
    }

    /** 停用服务；停用后三个协议的能力文档与瓦片请求都不再暴露该图层。 */
    @Transactional(rollbackFor = Exception.class)
    public GisOgcServiceVO disable(String serviceCode) {
        return updateStatus(serviceCode, DISABLED);
    }

    /** 重新启用已停用服务；产物不可用时拒绝启用。 */
    @Transactional(rollbackFor = Exception.class)
    public GisOgcServiceVO enable(String serviceCode) {
        GisOgcService service = required(serviceCode);
        GisTileSet tileSet = tileSetMapper.selectById(service.getTileSetId());
        if (tileSet == null || !AVAILABLE.equals(resolveAvailability(tileSet))) {
            throw new IllegalStateException("切片产物不可用，无法启用服务");
        }
        return updateStatus(serviceCode, PUBLISHED);
    }

    /** 按服务编码与对应协议解析瓦片文件；供三个 OGC 协议层取图复用。 */
    public Path resolveTileFile(String serviceCode, OgcProtocol protocol, String relativePath) {
        GisTileSet tileSet = serviceMapper.selectPublishedTileSet(serviceCode, protocol.name());
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

    /** 供 WMS 定位源 GeoTIFF；服务不存在时返回空瓦片集，由调用方处理。 */
    public GisTileSet publishedTileSet(String serviceCode, OgcProtocol protocol) {
        return serviceMapper.selectPublishedTileSet(serviceCode, protocol.name());
    }

    private GisOgcServiceVO updateStatus(String serviceCode, String status) {
        GisOgcService service = required(serviceCode);
        if (status.equals(service.getStatus())) {
            return toVO(service);
        }
        int affected = serviceMapper.update(null,
                new LambdaUpdateWrapper<GisOgcService>()
                        .eq(GisOgcService::getId, service.getId())
                        .set(GisOgcService::getStatus, status)
                        .set(GisOgcService::getUpdateTime, LocalDateTime.now()));
        if (affected != 1) {
            throw new IllegalStateException("OGC 服务状态更新失败: " + serviceCode);
        }
        service.setStatus(status);
        service.setUpdateTime(LocalDateTime.now());
        return toVO(service);
    }

    private GisOgcServiceVO toVO(GisOgcService service) {
        GisTileSet tileSet = tileSetMapper.selectById(service.getTileSetId());
        TileArtifact artifact = null;
        if (tileSet != null && AVAILABLE.equals(resolveAvailability(tileSet))) {
            try {
                artifact = inspectArtifact(tileSet, false);
            } catch (RuntimeException ex) {
                // 列表/详情只展示可用性，产物元数据异常时不影响其它字段
            }
        }
        return toVO(service, tileSet, artifact);
    }

    private GisOgcServiceVO toVO(GisOgcService service, GisTileSet tileSet,
            TileArtifact artifact) {
        String availability = tileSet == null
                ? "TILE_SET_NOT_READY" : resolveAvailability(tileSet);
        GisOgcServiceVO.GisOgcServiceVOBuilder builder = GisOgcServiceVO.builder()
                .id(service.getId())
                .serviceCode(service.getServiceCode())
                .resourceType(RASTER)
                .protocol(service.getProtocol())
                .serviceName(service.getServiceName())
                .layerName(service.getLayerName())
                .description(service.getDescription())
                .status(service.getStatus())
                .availability(availability)
                .sourceTaskId(service.getSourceTaskId())
                .tileSetId(service.getTileSetId())
                .dataSetId(service.getDataSetId())
                .minZoom(service.getMinZoom())
                .maxZoom(service.getMaxZoom())
                .targetCrs(service.getTargetCrs())
                .tileMatrixSet(service.getTileMatrixSet())
                .outputFormat(service.getOutputFormat())
                .tileProfile(service.getTileProfile())
                .serviceUrl(serviceUrl(service.getProtocol()))
                .capabilitiesUrl(capabilitiesUrl(service.getProtocol()))
                .tileUrlTemplate(tileUrlTemplate(service))
                .publishTime(service.getPublishTime())
                .updateTime(service.getUpdateTime());
        if (service.getDataSetId() != null) {
            GisDataSet dataSet = dataSetMapper.selectById(service.getDataSetId());
            if (dataSet != null) {
                builder.dataSetName(dataSet.getDataSetName());
            }
        }
        builder.taskName(taskService.getRequired(service.getSourceTaskId()).getTaskName());
        if (artifact != null && service.getProtocol() == OgcProtocol.WMTS) {
            builder.wmtsTileMatrixSetLimits(matrixLimits(service, artifact));
        }
        return builder.build();
    }

    /** 动态矢量发布记录不属于独立服务列表/启停接口，这里只返回发布结果与四套 OGC 地址。 */
    private GisOgcServiceVO vectorVO(GisPublicationVO publication) {
        String code = publication.getServiceCode();
        return GisOgcServiceVO.builder()
                .id(publication.getId())
                .serviceCode(code)
                .resourceType(VECTOR)
                .serviceName(publication.getServiceName())
                .layerName(publication.getServiceName())
                .status(publication.getStatus())
                .availability(AVAILABLE)
                .dataSetId(publication.getDataSetId())
                .dataSetName(publication.getDataSetName())
                .outputFormat("PNG")
                .serviceUrl(recordService.buildPublicUrl("/gis/wms"))
                .capabilitiesUrl(recordService.buildPublicUrl(
                        "/gis/wms?SERVICE=WMS&REQUEST=GetCapabilities"))
                .tileUrlTemplate(recordService.buildPublicUrl("/gis/wmts/rest/" + code
                        + "/{Style}/WebMercatorQuad/{TileMatrix}/{TileRow}/{TileCol}.png"))
                .wmsCapabilitiesUrl(recordService.buildPublicUrl(
                        "/gis/wms?SERVICE=WMS&REQUEST=GetCapabilities"))
                .wmtsCapabilitiesUrl(recordService.buildPublicUrl(
                        "/gis/wmts?SERVICE=WMTS&REQUEST=GetCapabilities"))
                .tmsCapabilitiesUrl(recordService.buildPublicUrl("/gis/tms/1.0.0"))
                .wfsCapabilitiesUrl(recordService.buildPublicUrl(
                        "/gis/wfs?SERVICE=WFS&REQUEST=GetCapabilities"))
                .vectorResourceId(publication.getVectorResourceId())
                .releaseVersion(publication.getReleaseVersion())
                .defaultStyleId(publication.getDefaultStyleId())
                .styles(publication.getStyles())
                .publishTime(publication.getPublishTime())
                .updateTime(publication.getUpdateTime())
                .build();
    }

    private String serviceUrl(OgcProtocol protocol) {
        return switch (protocol) {
            case WMTS -> recordService.buildPublicUrl("/gis/wmts");
            case WMS -> recordService.buildPublicUrl("/gis/wms");
            case TMS -> recordService.buildPublicUrl("/gis/tms/1.0.0");
        };
    }

    private String capabilitiesUrl(OgcProtocol protocol) {
        return switch (protocol) {
            case WMTS -> recordService.buildPublicUrl(
                    "/gis/wmts?SERVICE=WMTS&REQUEST=GetCapabilities");
            case WMS -> recordService.buildPublicUrl(
                    "/gis/wms?SERVICE=WMS&REQUEST=GetCapabilities");
            case TMS -> recordService.buildPublicUrl("/gis/tms/1.0.0");
        };
    }

    private String tileUrlTemplate(GisOgcService service) {
        String code = service.getServiceCode();
        String extension = "JPEG".equalsIgnoreCase(service.getOutputFormat()) ? "jpg" : "png";
        return switch (service.getProtocol()) {
            case WMTS -> recordService.buildPublicUrl("/gis/wmts/rest/" + code
                    + "/default/" + service.getTileMatrixSet()
                    + "/{TileMatrix}/{TileRow}/{TileCol}." + extension);
            case TMS -> recordService.buildPublicUrl("/gis/tms/1.0.0/" + code
                    + "@" + service.getTileMatrixSet() + "@" + extension
                    + "/{z}/{x}/{y}." + extension);
            case WMS -> null;
        };
    }

    private List<GisOgcServiceVO.WmtsMatrixLimitsVO> matrixLimits(
            GisOgcService service, TileArtifact artifact) {
        List<GisOgcServiceVO.WmtsMatrixLimitsVO> limits = new ArrayList<>();
        TileMatrixSetDefinition matrixSet = artifact.matrixSet();
        for (int zoom = service.getMinZoom(); zoom <= service.getMaxZoom(); zoom++) {
            if (matrixSet.matrixWidth(zoom) < 2 || matrixSet.matrixHeight(zoom) < 2) {
                continue;
            }
            TileMatrixSetDefinition.MatrixLimits range =
                    matrixSet.limits(zoom, artifact.wgs84Bounds());
            limits.add(GisOgcServiceVO.WmtsMatrixLimitsVO.builder()
                    .zoom(zoom)
                    .minRow(range.minRow())
                    .maxRow(range.maxRow())
                    .minCol(range.minCol())
                    .maxCol(range.maxCol())
                    .build());
        }
        return limits;
    }

    private String resolveAvailability(GisTileSet tileSet) {
        if (!READY.equals(tileSet.getTileSetStatus())) {
            return "TILE_SET_NOT_READY";
        }
        Path root;
        try {
            root = storageService.resolveTiles(tileSet.getOutputKey());
        } catch (RuntimeException ex) {
            return "DESCRIPTOR_MISSING";
        }
        Path descriptor = root.resolve("tilejson.json");
        if (!Files.isRegularFile(descriptor)) {
            return "DESCRIPTOR_MISSING";
        }
        try {
            readArtifact(descriptor);
        } catch (RuntimeException ex) {
            return "METADATA_INVALID";
        }
        return AVAILABLE;
    }

    private TileArtifact inspectArtifact(GisTileSet tileSet, boolean checkTiles) {
        Path root = storageService.resolveTiles(tileSet.getOutputKey());
        Path descriptor = root.resolve("tilejson.json");
        if (!READY.equals(tileSet.getTileSetStatus())) {
            throw new IllegalStateException("影像瓦片集未就绪，不能发布 OGC 服务");
        }
        if (!Files.isRegularFile(descriptor)) {
            throw new IllegalStateException("影像切片缺少 tilejson.json: " + root);
        }
        TileArtifact artifact = readArtifact(descriptor);
        if (checkTiles && !hasTiles(root, artifact.extension())) {
            throw new IllegalStateException(
                    "影像切片目录中没有 " + artifact.extension() + " 瓦片: " + root);
        }
        return artifact;
    }

    private TileArtifact readArtifact(Path descriptor) {
        JsonNode tileJson;
        try {
            tileJson = objectMapper.readTree(descriptor.toFile());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("无法读取图层描述文件: " + descriptor, ex);
        }
        String crs = tileJson.path("crs").asText();
        String declaredMatrixSet = tileJson.path("tileMatrixSet").asText();
        TileMatrixSetDefinition matrixSet = TileMatrixSetDefinition.resolve(declaredMatrixSet);
        if (matrixSet == null) {
            matrixSet = TileMatrixSetDefinition.resolveByCrs(crs);
        }
        if (matrixSet == null || (!declaredMatrixSet.isBlank()
                && !declaredMatrixSet.equalsIgnoreCase(matrixSet.id()))) {
            throw new IllegalStateException("影像切片坐标系与瓦片矩阵集不一致");
        }
        String format = tileJson.path("format").asText().toLowerCase(Locale.ROOT);
        String extension = format.startsWith("jpeg") || format.startsWith("jpg") ? "jpg" : "png";
        int minZoom = tileJson.path("minzoom").asInt(-1);
        int maxZoom = tileJson.path("maxzoom").asInt(-1);
        if (minZoom < 0 || maxZoom > TileMatrixSetDefinition.MAX_ZOOM || maxZoom < minZoom) {
            throw new IllegalStateException("影像切片层级范围不合法");
        }
        JsonNode boundsNode = tileJson.path("bounds");
        if (!boundsNode.isArray() || boundsNode.size() != 4) {
            throw new IllegalStateException("影像切片地理范围不合法");
        }
        double[] bounds = new double[4];
        for (int i = 0; i < 4; i++) {
            bounds[i] = boundsNode.get(i).asDouble();
        }
        return new TileArtifact(matrixSet, extension, minZoom, maxZoom, bounds);
    }

    private boolean hasTiles(Path root, String extension) {
        String suffix = "." + extension;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix));
        } catch (IOException ex) {
            throw new IllegalStateException("无法检查影像切片目录: " + root, ex);
        }
    }

    private GisOgcService required(String serviceCode) {
        String code = trimToNull(serviceCode);
        if (code == null) {
            throw new IllegalArgumentException("服务编码不能为空");
        }
        GisOgcService service = serviceMapper.selectOne(new LambdaQueryWrapper<GisOgcService>()
                .eq(GisOgcService::getServiceCode, code));
        if (service == null) {
            throw new IllegalArgumentException("OGC 服务不存在: " + code);
        }
        return service;
    }

    private String generateServiceCode(OgcProtocol protocol) {
        return protocol.name() + "_" + UUID.randomUUID().toString()
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

    private OgcProtocol parseNullableProtocol(String value) {
        String normalized = upperTrimToNull(value);
        return normalized == null ? null : OgcProtocol.parse(normalized);
    }

    private String requiredText(String value, String label, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能为空且最多 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String optionalText(String value, String label, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "最多 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String upperTrimToNull(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 发布时从 tilejson.json 读取的切片能力参数。 */
    private record TileArtifact(
            TileMatrixSetDefinition matrixSet,
            String extension,
            int minZoom,
            int maxZoom,
            double[] wgs84Bounds) {
    }
}
