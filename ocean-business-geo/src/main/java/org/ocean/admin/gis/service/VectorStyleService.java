package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.entity.GisVectorStyle;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.util.VectorTemplateRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.util.VectorStyleRules;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 图层样式独立版本管理；所有写操作沿用图层锁，默认切换与方案修改原子提交。 */
@Service
@RequiredArgsConstructor
public class VectorStyleService {
    private final GisVectorMapper mapper;
    private final VectorStyleRules rules;
    private final VectorLayerService layers;
    private final ObjectMapper json;
    private final VectorDataSetStyleService dataSetStyles;
    private final VectorTemplateRules templateRules;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<GisVectorVO.StyleSummary> list(Long layerId) {
        requireLayer(layerId, false);
        return mapper.styles(layerId).stream().map(style -> new GisVectorVO.StyleSummary(style.getId().toString(),
                layerId.toString(), style.getName(), style.getIsDefault(), style.getVersion(), style.getCreateTime(), style.getUpdateTime())).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.Style detail(Long layerId, Long id) {
        requireLayer(layerId, false);
        return toVO(requireStyle(layerId, id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.EffectiveStyle defaultStyle(Long layerId) {
        var layer = requireLayer(layerId, false);
        var snapshot = dataSetStyles.require(layer.getDataSetId(), false);
        var group = mapper.group(layer.getDataSetId(), layer.getGroupId());
        var custom = mapper.defaultStyle(layerId);
        JsonNode definition = effectiveDefinition(layer, group, dataSetStyles.compile(snapshot), custom);
        boolean configured = !"UNCONFIGURED".equals(layer.getStyleMode());
        return new GisVectorVO.EffectiveStyle(layerId.toString(), configured ? "CUSTOM".equals(layer.getStyleMode()) ? "CUSTOM" : "GROUP" : "UNCONFIGURED",
                group.getId().toString(), "CUSTOM".equals(layer.getStyleMode()) && custom != null ? custom.getId().toString() : group.getDefaultStyleKey(),
                "CUSTOM".equals(layer.getStyleMode()) && custom != null ? custom.getVersion() : snapshot.getStyleVersion(),
                layer.getPresentationVersion(), configured, definition);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Style create(Long layerId, GisVectorRequests.Style request) {
        GisVectorStyle style = parse(layerId, request);
        var layer = requireLayer(layerId, true);
        validateKind(layer, style);
        List<GisVectorStyle> existing = mapper.styles(layerId);
        if (existing.size() >= 100) throw error(HttpStatus.CONFLICT, "每个图层最多100份样式方案");
        requireUniqueName(existing, style.getName(), null);
        style.setId(IdWorker.getId());
        style.setIsDefault(false);
        if (mapper.insertStyle(style) != 1) throw new IllegalStateException("样式创建失败");
        return toVO(requireStyle(layerId, style.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Style update(Long layerId, Long id, String ifMatch, GisVectorRequests.Style request) {
        GisVectorStyle updated = parse(layerId, request);
        var layer = requireLayer(layerId, true);
        validateKind(layer, updated);
        GisVectorStyle existing = requireStyle(layerId, id);
        requireMatch(ifMatch, existing);
        requireUniqueName(mapper.styles(layerId), updated.getName(), id);
        updated.setId(id);
        if (mapper.updateStyle(updated, existing.getVersion()) != 1) throw stale();
        if (existing.getIsDefault() && "CUSTOM".equals(layer.getStyleMode())) touch(layer);
        return toVO(requireStyle(layerId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Style setDefault(Long layerId, Long id, String ifMatch) {
        var layer = requireLayer(layerId, true);
        GisVectorStyle style = requireStyle(layerId, id);
        validateKind(layer, style);
        requireMatch(ifMatch, style);
        if (!style.getIsDefault()) {
            mapper.clearDefaultStyle(layerId);
            if (mapper.setDefaultStyle(layerId, id, style.getVersion()) != 1) throw stale();
        }
        layer.setStyleMode("CUSTOM");
        touch(layer);
        return toVO(requireStyle(layerId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long layerId, Long id, String ifMatch) {
        requireLayer(layerId, true);
        GisVectorStyle style = requireStyle(layerId, id);
        requireMatch(ifMatch, style);
        if (style.getIsDefault()) throw error(HttpStatus.CONFLICT, "请先切换其他默认样式，再删除本方案");
        if (mapper.deleteStyle(layerId, id, style.getVersion()) != 1) throw stale();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.StyleMatch match(Long layerId, Long id, GisVectorRequests.StyleMatch request) {
        if (request == null || request.featureId() == null || request.featureId() <= 0 || request.zoom() == null) {
            throw error(HttpStatus.BAD_REQUEST, "featureId 与 zoom 必填");
        }
        try { rules.requireZoom(request.zoom()); }
        catch (IllegalArgumentException ex) { throw error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        requireLayer(layerId, false);
        GisVectorStyle style = requireStyle(layerId, id);
        var feature = layers.feature(layerId, request.featureId());
        return new GisVectorVO.StyleMatch(id.toString(), layerId.toString(), feature.id(), style.getVersion(), feature.version(),
                request.zoom(), rules.match(json.readTree(style.getDefinitionJson()), feature.geometry(), feature.properties(), request.zoom()));
    }

    public static String etag(String id, long version) { return "\"style-" + id + "-v" + version + "\""; }

    private GisVectorStyle parse(Long layerId, GisVectorRequests.Style request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().trim().length() > 100) {
            throw error(HttpStatus.BAD_REQUEST, "样式名称须为1至100个字符");
        }
        try {
            GisVectorStyle style = new GisVectorStyle();
            style.setLayerId(layerId);
            style.setName(request.name().trim());
            style.setDefinitionJson(json.writeValueAsString(rules.validate(request.definition())));
            return style;
        } catch (IllegalArgumentException ex) { throw error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    private GisVectorLayer requireLayer(Long id, boolean lock) {
        if (id == null || id <= 0) throw error(HttpStatus.BAD_REQUEST, "图层ID须大于0");
        var existing = mapper.layer(id);
        if (existing == null) throw error(HttpStatus.NOT_FOUND, "图层不存在或已删除");
        if (lock) dataSetStyles.require(existing.getDataSetId(), true);
        var layer = lock ? mapper.lockLayer(id) : existing;
        if (layer == null || layer.getGroupId() == null) throw error(HttpStatus.CONFLICT, "图层尚未分类或已变化");
        return layer;
    }

    private void validateKind(GisVectorLayer layer, GisVectorStyle style) {
        for (var rule : json.readTree(style.getDefinitionJson()).path("rules")) {
            if (!layer.getGeometryType().equals(rule.path("geometryType").asText())) throw error(HttpStatus.BAD_REQUEST, "样式只能使用当前图层的几何类别");
            try {
                templateRules.bounds(rule.path("minZoom").asDouble(0), rule.path("maxZoom").asDouble(22));
                for (String name : java.util.List.of("radius", "width", "strokeWidth")) if (rule.path("symbol").has(name)) {
                    double size = rule.path("symbol").path(name).asDouble();
                    if (size > 12 || size < ("strokeWidth".equals(name) ? 0 : .5)) throw new IllegalArgumentException("符号尺寸超出数据集允许范围");
                }
            } catch (IllegalArgumentException ex) { throw error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
        }
    }

    private void touch(GisVectorLayer layer) {
        if (mapper.updatePresentation(layer, layer.getPresentationVersion()) != 1) throw stale();
        mapper.bumpDataSetRevision(layer.getDataSetId(), false, true);
    }

    /** 输出内部显示规则，不把模板协议转换存储为图层协议。 */
    public JsonNode effectiveDefinition(GisVectorLayer layer, GisVectorGroup group,
            VectorTemplateRules.Compiled template, GisVectorStyle custom) {
        JsonNode base = templateRules.groupStyle(template, group.getGroupKey());
        ObjectNode result = json.createObjectNode();
        result.set("labels", base.path("labels").deepCopy());
        result.put("renderOrder", base.path("renderOrder").asInt());
        boolean customMode = "CUSTOM".equals(layer.getStyleMode());
        result.put("normalized", !customMode);
        result.put("zoomBeforeMatch", customMode);
        if ("UNCONFIGURED".equals(layer.getStyleMode()) || !customMode && !base.path("enabled").asBoolean()) result.set("rules", json.createArrayNode());
        else if (customMode) {
            if (custom == null) throw error(HttpStatus.CONFLICT, "图层自定义样式缺失");
            JsonNode definition = rules.validate(json.readTree(custom.getDefinitionJson()));
            validateKind(layer, custom);
            result.set("rules", definition.path("rules").deepCopy());
        } else {
            if ("UNKNOWN_CLASSIFICATION".equals(group.getNodeType())) throw error(HttpStatus.CONFLICT, "未知组不能继承默认业务样式");
            result.set("rules", templateRules.displayRules(base, "AUTO".equals(layer.getAssignmentMode())));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Layer savePresentation(Long layerId, String ifMatch, GisVectorRequests.Presentation request) {
        var layer = requireLayer(layerId, true);
        VectorDataSetStyleService.requireMatch(ifMatch, VectorLayerService.layerEtag(layerId, layer.getDataVersion(), layer.getPresentationVersion()));
        var target = request.groupId() == null ? null : mapper.group(layer.getDataSetId(), request.groupId());
        if (target == null || !Boolean.TRUE.equals(target.getAssignable()) || !layer.getGeometryType().equals(target.getGeometryType()))
            throw error(HttpStatus.BAD_REQUEST, "目标须为同一数据集内几何类别一致的可挂载分类");
        boolean custom = "CUSTOM".equals(request.styleMode());
        if (!custom && !"INHERIT_TARGET_GROUP".equals(request.styleMode())) throw error(HttpStatus.BAD_REQUEST, "样式模式无效");
        if (!custom && "UNKNOWN_CLASSIFICATION".equals(target.getNodeType())) throw error(HttpStatus.BAD_REQUEST, "未知组必须显式配置样式");
        if (custom) savePresentationStyle(layer, target, request.customStyle());
        else if (request.customStyle() != null) throw error(HttpStatus.BAD_REQUEST, "继承分组时不能同时提交自定义样式");
        layer.setGroupId(target.getId());
        layer.setAssignmentMode("MANUAL");
        layer.setStyleMode(request.styleMode());
        layer.setEnabled(Boolean.TRUE.equals(request.enabled()));
        touch(layer);
        return layers.detail(layerId);
    }

    private void savePresentationStyle(GisVectorLayer layer, GisVectorGroup target, GisVectorRequests.CustomStyle request) {
        if (request == null || request.minZoom() == null || request.maxZoom() == null || request.symbol() == null)
            throw error(HttpStatus.BAD_REQUEST, "自定义样式须提供完整级别与符号");
        try {
            templateRules.bounds(request.minZoom(), request.maxZoom());
            var template = dataSetStyles.compile(dataSetStyles.require(layer.getDataSetId(), false));
            var symbol = templateRules.symbol(layer.getGeometryType(), templateRules.merge(
                    templateRules.groupStyle(template, target.getGroupKey()).path("symbol"), request.symbol()));
            for (String field : java.util.List.of("radius", "width", "strokeWidth")) if (symbol.has(field)) {
                double size = symbol.path(field).asDouble();
                if (size > 12 || size < ("strokeWidth".equals(field) ? 0 : .5)) throw new IllegalArgumentException("符号尺寸须在允许范围内");
            }
            ObjectNode rule = json.createObjectNode().put("id", "custom").put("geometryType", layer.getGeometryType())
                    .put("minZoom", request.minZoom()).put("maxZoom", request.maxZoom()).put("visible", true);
            rule.set("symbol", symbol);
            ObjectNode definition = json.createObjectNode().put("schemaVersion", 1);
            definition.set("rules", json.createArrayNode().add(rule));
            var style = mapper.defaultStyle(layer.getId());
            boolean insert = style == null;
            if (insert) {
                if (mapper.styles(layer.getId()).size() >= 100) throw new IllegalArgumentException("图层样式方案已达上限");
                style = new GisVectorStyle();
                style.setId(IdWorker.getId()); style.setLayerId(layer.getId());
                style.setName("手动样式-" + style.getId()); style.setIsDefault(true);
            }
            style.setDefinitionJson(json.writeValueAsString(rules.validate(definition)));
            if ((insert ? mapper.insertStyle(style) : mapper.updateStyle(style, style.getVersion())) != 1) throw stale();
        } catch (IllegalArgumentException ex) { throw error(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    private GisVectorStyle requireStyle(Long layerId, Long id) {
        if (id == null || id <= 0) throw error(HttpStatus.BAD_REQUEST, "样式ID须大于0");
        var style = mapper.style(layerId, id);
        if (style == null) throw error(HttpStatus.NOT_FOUND, "图层内不存在该样式");
        return style;
    }

    private void requireUniqueName(List<GisVectorStyle> existing, String name, Long except) {
        if (existing.stream().anyMatch(style -> name.equals(style.getName()) && !style.getId().equals(except))) {
            throw error(HttpStatus.CONFLICT, "同一图层的样式名称不能重复");
        }
    }

    private void requireMatch(String value, GisVectorStyle style) {
        if (value == null || value.isBlank()) throw error(HttpStatus.PRECONDITION_REQUIRED, "修改样式必须携带最新 If-Match/ETag");
        if (!etag(style.getId().toString(), style.getVersion()).equals(value.trim())) throw stale();
    }

    private GisVectorVO.Style toVO(GisVectorStyle style) {
        return new GisVectorVO.Style(style.getId().toString(), style.getLayerId().toString(), style.getName(),
                style.getIsDefault(), json.readTree(style.getDefinitionJson()), style.getVersion(), style.getCreateTime(), style.getUpdateTime());
    }

    private ResponseStatusException stale() { return error(HttpStatus.PRECONDITION_FAILED, "样式已变化，请重新加载后编辑"); }
    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
