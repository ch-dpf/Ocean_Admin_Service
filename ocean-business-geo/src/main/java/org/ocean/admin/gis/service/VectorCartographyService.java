package org.ocean.admin.gis.service;

import org.ocean.admin.gis.mapper.GisVectorStyleMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.util.VectorTemplateRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 分类与要素制图覆盖；样式修订在业务事务内原子更新。 */
@Service
@RequiredArgsConstructor
public class VectorCartographyService {

    private final GisVectorStyleMapper gisVectorStyleMapper;
    private final ObjectMapper json;
    private final VectorDataSetStyleService dataSetStyles;
    private final VectorTemplateRules templateRules;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.Cartography cartography(Long dataSetId) {
        var snapshot = dataSetStyles.require(dataSetId, false);
        var template = dataSetStyles.compile(snapshot);
        var overrides = json.readTree(gisVectorStyleMapper.groupCartography(dataSetId));
        var groups = gisVectorStyleMapper.groups(dataSetId);
        var byId = groups.stream().collect(java.util.stream.Collectors.toMap(GisVectorGroup::getId, g -> g));
        var result = json.createObjectNode();
        for (var group : groups) {
            boolean enabled = true;
            var ancestor = group;
            while (ancestor != null) {
                enabled &= Boolean.TRUE.equals(ancestor.getEnabled());
                ancestor = byId.get(ancestor.getParentId());
            }
            var node = json.createObjectNode().put("id",group.getId().toString()).put("enabled",enabled)
                    .put("styleKey",group.getDefaultStyleKey());
            node.set("override",overrides.path(group.getGroupKey()));
            result.set(group.getGroupKey(),node);
        }
        var layerConfig = json.readTree(gisVectorStyleMapper.layerCartography(dataSetId));
        if (json.writeValueAsBytes(layerConfig).length > 4 * 1024 * 1024)
            throw error(HttpStatus.UNPROCESSABLE_CONTENT,"图层配图元数据超过4MiB，请拆分数据集");
        return new GisVectorVO.Cartography(dataSetId.toString(), snapshot.getStyleVersion(), snapshot.getDisplayRevision(),
                template.definition().path("cartography"), result, layerConfig, gisVectorStyleMapper.hasFeatureCartography(dataSetId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JsonNode featureCartography(Long dataSetId, List<Long> ids) {
        dataSetStyles.require(dataSetId, false);
        if (ids == null || ids.isEmpty() || ids.size() > 256 || ids.stream().anyMatch(id -> id == null || id < 1))
            throw error(HttpStatus.BAD_REQUEST,"每批须提供1至256个要素ID");
        return json.readTree(gisVectorStyleMapper.featureCartography(dataSetId,ids));
    }

    /** 覆盖仅保存显式字段，未设置字段继承分组/属性规则，不污染几何版本。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveCartography(Long dataSetId, Long targetId, String scope, JsonNode patch, String ifMatch) {
        var snapshot = dataSetStyles.require(dataSetId, true);
        VectorDataSetStyleService.requireMatch(ifMatch, cartographyEtag(snapshot.getDataSetId(),snapshot.getStyleVersion()));
        String kind;
        if ("GROUP".equals(scope)) {
            var group = gisVectorStyleMapper.group(dataSetId,targetId);
            if (group == null || !Boolean.TRUE.equals(group.getAssignable())) throw error(HttpStatus.NOT_FOUND,"分类不存在或不可配图");
            kind = group.getGeometryType();
        } else {
            kind = gisVectorStyleMapper.featureKind(dataSetId,targetId);
            if (kind == null) throw error(HttpStatus.NOT_FOUND,"要素不属于当前数据集");
        }
        try {
            if (patch == null || !patch.isObject() || !patch.path("symbol").isObject()
                    || json.writeValueAsBytes(patch).length > 16384) throw new IllegalArgumentException("须提供不超过16KiB的symbol字段覆盖");
            for (String field : patch.propertyNames())
                if (!List.of("schemaVersion","symbol","visible").contains(field)) throw new IllegalArgumentException("不支持的配图字段: " + field);
            if (patch.has("visible") && !patch.path("visible").isBoolean()) throw new IllegalArgumentException("visible须为布尔值");
            var defaults = json.createObjectNode();
            if ("POLYGON".equals(kind)) defaults.put("fillColor","#ffffff").put("fillOpacity",.5).put("strokeColor","#000000").put("strokeWidth",1).put("strokeOpacity",1);
            else if ("LINE".equals(kind)) defaults.put("color","#000000").put("width",1).put("opacity",1);
            else defaults.put("color","#000000").put("radius",6).put("opacity",1).put("strokeColor","#000000").put("strokeWidth",0);
            templateRules.symbol(kind,templateRules.merge(defaults,patch.path("symbol")));
            for (String field : List.of("radius","width","strokeWidth"))
                if (patch.path("symbol").has(field) && patch.path("symbol").path(field).asDouble() > 12)
                    throw new IllegalArgumentException("覆盖符号尺寸不能超过12 CSS像素");
            var definition = (ObjectNode) patch.deepCopy();
            definition.put("schemaVersion",1);
            gisVectorStyleMapper.saveCartography(IdWorker.getId(),scope,targetId,json.writeValueAsString(definition));
        } catch (IllegalArgumentException ex) { throw error(HttpStatus.BAD_REQUEST,ex.getMessage()); }
        gisVectorStyleMapper.bumpDataSetRevision(dataSetId,false,true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFeatureCartography(Long dataSetId, Long featureId, String ifMatch) {
        var snapshot = dataSetStyles.require(dataSetId,true);
        VectorDataSetStyleService.requireMatch(ifMatch,cartographyEtag(dataSetId,snapshot.getStyleVersion()));
        if (gisVectorStyleMapper.featureKind(dataSetId,featureId) == null) throw error(HttpStatus.NOT_FOUND,"要素不属于当前数据集");
        if (gisVectorStyleMapper.deleteFeatureCartography(featureId) > 0) gisVectorStyleMapper.bumpDataSetRevision(dataSetId,false,true);
    }

    public static String cartographyEtag(Long id, long version) { return "\"cartography-2-" + id + "-s" + version + "\""; }
    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
