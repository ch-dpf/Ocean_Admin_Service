package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 数据集直接发布动态 WMS；固定工作数据和样式，不依赖处理任务或静态瓦片。 */
@Service
@RequiredArgsConstructor
public class VectorDatasetPublicationService {
    private final GisDataSetMapper dataSets;
    private final GisPublicationMapper records;
    private final VectorDataSetStyleService dataSetStyles;
    private final VectorStyleSchemeService schemes;
    private final VectorResourceService resources;
    private final PublicationRecordService addresses;
    private final PublicationService publications;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public GisPublicationVO publish(GisVectorRequests.Publish request) throws IOException {
        requireDataSet(request.dataSetId());
        var state = dataSetStyles.require(request.dataSetId(), true);
        String definition = schemes.publicationDefinition(request.dataSetId(), request.styleSchemeIds(), request.defaultStyleSchemeId());
        JsonNode binding = json.readTree(definition);
        for (var scheme : binding.path("styles")) schemes.validateScheme(state, scheme.path("definition"));
        var resource = resources.freeze(request.dataSetId(), "PUBLICATION");

        String code = "VEC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        String wms = addresses.buildPublicUrl("/gis/wms");
        var record = new GisPublication();
        record.setServiceCode(code);
        record.setServiceName(request.serviceName().strip());
        record.setServiceProtocol("WMS");
        record.setProtocolsJson(json.writeValueAsString(List.of("WMS")));
        record.setProcessingType("VECTOR");
        record.setLayerName(code);
        record.setTitle(record.getServiceName());
        record.setDefaultCrs("EPSG:3857");
        record.setSupportedCrsJson(json.writeValueAsString(List.of("EPSG:3857", "EPSG:4326", "CRS:84")));
        record.setTileMatrixSetsJson("[]");
        record.setMinZoom(0);
        record.setMaxZoom(Math.min(22, json.readTree(resource.getDefinitionJson()).path("map").path("nativeMaxZoom").asInt(18)));
        record.setTargetCrs("EPSG:3857");
        record.setOutputFormat("PNG");
        record.setBoundsWgs84Json(json.writeValueAsString(List.of(-180d, -85.05112877980659d, 180d, 85.05112877980659d)));
        record.setStyleId(binding.path("defaultStyleId").asText());
        record.setStyleName(defaultStyleName(binding));
        record.setPublicBaseUrl(addresses.publicBaseUrl());
        record.setCapabilitiesUrlsJson(json.writeValueAsString(Map.of("WMS", wms + "?SERVICE=WMS&REQUEST=GetCapabilities")));
        record.setServiceUrlsJson(json.writeValueAsString(Map.of("WMS", wms)));
        record.setTileUrlTemplatesJson("{}");
        record.setVectorResourceId(resource.getId());
        record.setVectorDefinition(definition);
        record.setDataSetId(request.dataSetId());
        record.setStatus("PUBLISHED");
        record.setPublishTime(LocalDateTime.now());
        record.setUpdateTime(record.getPublishTime());
        record.setDeleted(0);
        if (records.insert(record) != 1) throw new IllegalStateException("数据集 WMS 发布记录保存失败");
        saveRevision(record.getId(), binding, definition);
        return publications.get(GisProcessingType.VECTOR, code);
    }

    /** 样式更新沿用已冻结的数据；版本冲突和停用状态在持锁后再次检查。 */
    @Transactional(rollbackFor = Exception.class)
    public GisPublicationVO updateStyles(String serviceCode, String ifMatch, GisVectorRequests.Publish request) {
        requireDataSet(request.dataSetId());
        var state = dataSetStyles.require(request.dataSetId(), true);
        var record = records.lockVectorPublication(serviceCode);
        if (record == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集 WMS 服务不存在");
        if (record.getSourceTaskId() != null || !"WMS".equals(record.getServiceProtocol())
                || !request.dataSetId().equals(record.getDataSetId()) || record.getVectorResourceId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能更新当前数据集直接发布的 WMS 样式");
        if (!"PUBLISHED".equals(record.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "服务已停用，不能更新发布样式");
        JsonNode previous = json.readTree(record.getVectorDefinition());
        VectorDataSetStyleService.requireMatch(ifMatch, "\"release-" + previous.path("releaseVersion").asText() + "\"");
        String definition = schemes.publicationDefinition(request.dataSetId(), request.styleSchemeIds(), request.defaultStyleSchemeId());
        JsonNode binding = json.readTree(definition);
        JsonNode frozen = json.readTree(resources.require(record.getVectorResourceId()).getDefinitionJson());
        for (var scheme : binding.path("styles")) {
            schemes.validateScheme(state, scheme.path("definition"));
            validateFrozenTargets(frozen, scheme.path("definition").path("cartography"));
        }
        if (records.updateDataSetStyles(record.getId(), record.getVectorDefinition(), definition,
                binding.path("defaultStyleId").asText(), defaultStyleName(binding)) != 1)
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "发布版本已变化，请重新加载");
        saveRevision(record.getId(), binding, definition);
        return publications.get(GisProcessingType.VECTOR, serviceCode);
    }

    private void requireDataSet(Long id) {
        var dataSet = dataSets.selectActiveByIdForUpdate(id);
        if (dataSet == null || !Long.valueOf(2).equals(dataSet.getCategoryId()))
            throw new IllegalArgumentException("矢量数据集不存在或已删除");
    }

    private void validateFrozenTargets(JsonNode frozen, JsonNode cartography) {
        var groups = new java.util.HashMap<String, String>();
        for (var group : frozen.path("groups")) groups.put(group.path("groupKey").asText(), group.path("id").asText());
        for (String key : cartography.path("groups").propertyNames()) {
            if (!cartography.path("groups").path(key).path("id").asText().equals(groups.get(key)))
                throw new IllegalArgumentException("样式分类与已发布数据版本不一致，请重新发布数据集");
        }
        var layers = new java.util.HashSet<String>();
        for (var layer : frozen.path("layers")) layers.add(layer.path("id").asText());
        for (String id : cartography.path("layers").propertyNames()) {
            if (!layers.contains(id)) throw new IllegalArgumentException("样式包含发布快照之外的图层，请重新发布数据集");
        }
    }

    private String defaultStyleName(JsonNode binding) {
        for (var scheme : binding.path("styles")) {
            if (binding.path("defaultStyleId").asText().equals(scheme.path("id").asText())) return scheme.path("name").asText();
        }
        throw new IllegalArgumentException("默认样式不属于所选方案");
    }

    private void saveRevision(Long id, JsonNode binding, String definition) {
        if (records.insertVectorRevision(id, binding.path("releaseVersion").asText(), definition) != 1)
            throw new IllegalStateException("发布样式版本保存失败");
    }
}
