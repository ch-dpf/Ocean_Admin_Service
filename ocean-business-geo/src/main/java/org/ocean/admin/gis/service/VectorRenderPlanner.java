package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.entity.*;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** 规划整个视窗而非逐图层限量；数据库只输出计划允许的显示对象。 */
@Service
@RequiredArgsConstructor
public class VectorRenderPlanner {
    private final GisVectorMapper mapper;
    private final VectorDataSetStyleService snapshots;
    private final VectorStyleService styles;
    private final ObjectMapper json;

    public GisVectorVO.RenderContext plan(GisVectorDataSetStyle snapshot, GisVectorRequests.RenderContext request, Instant expiresAt) {
        validate(request);
        var template = snapshots.compile(snapshot);
        ObjectNode policy = (ObjectNode) template.definition().path("renderPolicy").deepCopy();
        // 运行时只使用保形显示策略，不执行快照中的聚合元数据。
        policy.remove("aggregation");
        policy.remove("onAggregationUnavailable");
        ((ObjectNode) policy.path("densityBudget")).remove("countAggregatesAsOneDisplayObject");
        policy.put("pointAggregation", false);
        policy.put("onBudgetExceeded", "GEOMETRY_PRESERVING_LOD_OR_REPORT_LIMIT");
        if (request.displayZoom() < policy.path("minZoom").asDouble() || request.displayZoom() > policy.path("maxZoom").asDouble())
            throw bad("显示层级超出数据集策略");
        List<GisVectorGroup> groups = mapper.groups(snapshot.getDataSetId());
        Map<Long, GisVectorGroup> byId = groups.stream().collect(Collectors.toMap(GisVectorGroup::getId, g -> g));
        if (request.enabledGroupIds() != null && !byId.keySet().containsAll(request.enabledGroupIds())) throw bad("分组不属于当前数据集");
        Map<Long, Boolean> eligibleGroups = new HashMap<>();
        for (var group : groups) {
            boolean allowed = true, selected = request.enabledGroupIds() == null, exempt = false;
            GisVectorGroup ancestor = group;
            while (ancestor != null) {
                if (!Boolean.TRUE.equals(ancestor.getEnabled())) allowed = false;
                if (request.enabledGroupIds() != null && request.enabledGroupIds().contains(ancestor.getId())) selected = true;
                for (JsonNode key : policy.path("pixelFilters").path("polygonExemptGroupParentIds"))
                    if (ancestor.getGroupKey().equals(key.asText())) exempt = true;
                ancestor = byId.get(ancestor.getParentId());
            }
            if (allowed && selected && Boolean.TRUE.equals(group.getAssignable())) eligibleGroups.put(group.getId(), exempt);
        }
        var layers = eligibleGroups.isEmpty() || request.enabledLayerIds() != null && request.enabledLayerIds().isEmpty()
                ? List.<GisVectorLayer>of() : mapper.renderLayers(snapshot.getDataSetId(), List.copyOf(eligibleGroups.keySet()), request.enabledLayerIds());
        if (layers.size() > 1024) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "启用图层超过1024个，请减少启用图层");
        var customStyles = layers.isEmpty() ? List.<GisVectorStyle>of()
                : mapper.defaultStyles(snapshot.getDataSetId(), layers.stream().map(GisVectorLayer::getId).toList());
        if (customStyles.stream().anyMatch(s -> s.getDefinitionJson() == null))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "自定义显示规则总量超过1MiB，请减少启用图层或规则");
        Map<Long, GisVectorStyle> custom = customStyles.stream().collect(Collectors.toMap(GisVectorStyle::getLayerId, s -> s));
        ArrayNode plans = json.createArrayNode();
        for (var layer : layers) {
            var group = byId.get(layer.getGroupId());
            if (group == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "图层分组不存在");
            var definition = styles.effectiveDefinition(layer, group, template, custom.get(layer.getId()));
            if (definition.path("rules").isEmpty()) continue;
            boolean visibleAtZoom = false;
            for (var rule : definition.path("rules")) {
                if (rule.path("visible").asBoolean(true) && request.displayZoom() >= rule.path("minZoom").asDouble(0)
                        && request.displayZoom() < rule.path("maxZoom").asDouble(22)) visibleAtZoom = true;
            }
            // 整层不可能显示时不发起空间查询；仍保留可显示层中的隐藏覆盖规则，避免错误回落到基础样式。
            if (!visibleAtZoom) continue;
            ObjectNode p = (ObjectNode) definition.deepCopy();
            p.put("layerId", layer.getId().toString());
            p.put("groupId", group.getId().toString());
            p.put("kind", layer.getGeometryType());
            p.put("pixelExempt", eligibleGroups.get(group.getId()));
            plans.add(p);
        }
        if (json.writeValueAsBytes(plans).length > 1024 * 1024) throw bad("显示规则过大，请减少启用图层或规则");
        int budget = budget(policy, request.widthPixels(), request.heightPixels());
        String plansJson = json.writeValueAsString(plans), policyJson = json.writeValueAsString(policy);
        var preview = context(snapshot, request, expiresAt, budget, new GisVectorVO.DisplayPlan(0, 0, null, null), plans, policy);
        var display = plans.isEmpty() ? new GisVectorVO.DisplayPlan(0, 0, null, null) : mapper.displayPlan(preview, plansJson, policyJson);
        if (display.objectCount() > budget || display.candidateCount() > 0 && display.objectCount() == 0)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "保形显示无法满足当前预算，请放大或减少启用分类；点要素不聚合，行政边界不以聚合点替代");
        return context(snapshot, request, expiresAt, budget, display, plans, policy);
    }

    private GisVectorVO.RenderContext context(GisVectorDataSetStyle s, GisVectorRequests.RenderContext r, Instant expires,
            int budget, GisVectorVO.DisplayPlan display, JsonNode plans, JsonNode policy) {
        return new GisVectorVO.RenderContext(null, s.getDataSetId().toString(), s.getDataRevision(), s.getDisplayRevision(),
                s.getStyleVersion(), List.copyOf(r.bbox()), r.displayZoom(), r.widthPixels(), r.heightPixels(), budget,
                display.objectCount(), display.candidateCount(), display.candidateCount() > display.objectCount(),
                display.cutoffMinZoom(), display.cutoffPixelSize(), policy.path("maxZoom").asInt(), plans, policy, expires);
    }

    public int budget(JsonNode policy, int width, int height) {
        var b = policy.path("densityBudget");
        return (int) Math.max(b.path("minObjects").asLong(), Math.min(b.path("maxObjects").asLong(),
                (long) width * height / b.path("pixelsPerObject").asLong()));
    }

    public void validate(GisVectorRequests.RenderContext r) {
        if (r == null || r.bbox() == null || r.bbox().size() != 4 || r.bbox().stream().anyMatch(n -> n == null || !Double.isFinite(n))
                || r.displayZoom() == null || !Double.isFinite(r.displayZoom()) || r.widthPixels() == null || r.heightPixels() == null
                || r.widthPixels() < 1 || r.heightPixels() < 1 || r.widthPixels() > 8192 || r.heightPixels() > 8192
                || r.enabledGroupIds() != null && r.enabledGroupIds().size() > 1024
                || r.enabledLayerIds() != null && r.enabledLayerIds().size() > 1024) throw bad("须提供有效bbox、连续显示层级和1至8192的CSS视窗宽高");
        var b = r.bbox();
        if (Math.abs(b.get(0)) > 180 || Math.abs(b.get(2)) > 180 || b.get(1) < -85.0511287798066
                || b.get(3) > 85.0511287798066 || b.get(1) >= b.get(3) || b.get(0).doubleValue() == b.get(2).doubleValue()) throw bad("bbox须为有效Web Mercator视窗，经度允许跨日期变更线");
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
