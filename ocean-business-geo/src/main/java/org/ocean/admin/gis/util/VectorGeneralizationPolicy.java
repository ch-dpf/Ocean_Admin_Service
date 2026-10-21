package org.ocean.admin.gis.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 概化策略声明几何处理、分组字段和可验证的显示预算，不改变原始要素。 */
public record VectorGeneralizationPolicy(boolean enabled, int detailMinZoom, List<String> groupFields,
        String lineClassField, Map<String,Integer> lineMinZoom, int defaultLineMinZoom,
        String polygonMode, double tolerancePixels, int cellPixels, double aggregationPixels,
        double minPolygonPixels, int maxBufferPixels) {
    private static final Set<String> KEYS = Set.of("enabled","detailMinZoom","groupFields","lineClassField",
            "lineMinZoom","defaultLineMinZoom","polygonMode","tolerancePixels","cellPixels",
            "aggregationPixels","minPolygonPixels","maxBufferPixels");

    public static VectorGeneralizationPolicy defaults(boolean enabled) {
        return new VectorGeneralizationPolicy(enabled,13,List.of(),null,Map.of(),0,
                "SIMPLIFY",0.75,64,3,2,128);
    }

    public static VectorGeneralizationPolicy parse(JsonNode node, ObjectMapper json) {
        if (node == null || !node.isObject()) throw bad("概化策略须为对象");
        node.propertyNames().forEach(name -> { if (!KEYS.contains(name)) throw bad("未知概化参数: " + name); });
        VectorGeneralizationPolicy p;
        try { p = json.treeToValue(node,VectorGeneralizationPolicy.class); }
        catch (RuntimeException ex) { throw bad("概化策略参数类型无效"); }
        if (p.detailMinZoom < 1 || p.detailMinZoom > 16) throw bad("detailMinZoom 须为1至16");
        if (p.groupFields == null || p.groupFields.size() > 8 || new HashSet<>(p.groupFields).size()!=p.groupFields.size()) throw bad("groupFields 须为最多8个不重复字段");
        for (String field : p.groupFields) if (field == null || field.isBlank() || field.length()>255) throw bad("分组字段名无效");
        if (p.lineMinZoom == null || p.lineMinZoom.size()>128) throw bad("道路等级映射最多128项");
        for (var e : p.lineMinZoom.entrySet()) if (e.getKey().length()>255 || e.getValue()==null || e.getValue()<0 || e.getValue()>22) throw bad("道路最小层级须为0至22");
        if (p.lineClassField != null && !p.groupFields.contains(p.lineClassField)) throw bad("道路等级字段须同时列入groupFields，避免混合类别");
        if (!p.lineMinZoom.isEmpty() && p.lineClassField==null) throw bad("道路等级映射须声明lineClassField");
        if (p.defaultLineMinZoom<0 || p.defaultLineMinZoom>22) throw bad("defaultLineMinZoom 须为0至22");
        if (!Set.of("SIMPLIFY","BUILDINGS","COVERAGE").contains(p.polygonMode)) throw bad("polygonMode 须为SIMPLIFY、BUILDINGS或COVERAGE");
        if (!Double.isFinite(p.tolerancePixels) || p.tolerancePixels<0.1 || p.tolerancePixels>4) throw bad("tolerancePixels 须为0.1至4");
        if (p.cellPixels<16 || p.cellPixels>128 || 256%p.cellPixels!=0) throw bad("cellPixels 须为16、32、64或128");
        if (!Double.isFinite(p.aggregationPixels) || p.aggregationPixels<1 || p.aggregationPixels>16) throw bad("aggregationPixels 须为1至16");
        if (!Double.isFinite(p.minPolygonPixels) || p.minPolygonPixels<0 || p.minPolygonPixels>64) throw bad("minPolygonPixels 须为0至64");
        if (p.maxBufferPixels<0 || p.maxBufferPixels>256) throw bad("maxBufferPixels 须为0至256");
        return p;
    }

    public List<String> unsupported(List<String> fields) { return fields.stream().filter(f -> !groupFields.contains(f)).toList(); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
