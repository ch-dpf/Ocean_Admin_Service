package org.ocean.admin.gis.service;

import org.ocean.admin.gis.mapper.GisVectorFeatureMapper;
import org.ocean.admin.gis.mapper.GisVectorStyleMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.util.VectorTemplateRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import org.ocean.admin.gis.util.VectorStyleRules;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;

/** 独立样式方案的导入、快照、版本管理和发布校验。 */
@Service
@RequiredArgsConstructor
public class VectorStyleSchemeService {
    private final VectorCartographyService cartography;

    private final GisVectorFeatureMapper gisVectorFeatureMapper;
    private final GisVectorStyleMapper gisVectorStyleMapper;
    private final VectorStyleRules rules;
    private final ObjectMapper json;
    private final VectorDataSetStyleService dataSetStyles;
    private final VectorTemplateRules templateRules;

    public List<GisVectorVO.Scheme> schemes(Long dataSetId) {
        dataSetStyles.require(dataSetId,false);
        return gisVectorStyleMapper.schemes(dataSetId).stream().map(this::schemeVO).toList();
    }

    /** 卡片列表仅返回名称、版本和三个代表色，选中时再读取完整方案。 */
    public List<GisVectorVO.SchemeOption> schemeOptions(Long dataSetId) {
        dataSetStyles.require(dataSetId, false);
        return gisVectorStyleMapper.schemeOptions(dataSetId).stream().map(s -> new GisVectorVO.SchemeOption(
                s.getId().toString(), s.getName(), s.getVersion(), json.readTree(s.getDefinitionJson()))).toList();
    }

    public GisVectorVO.Scheme scheme(Long dataSetId,Long id) {
        var scheme=gisVectorStyleMapper.scheme(id);
        if(scheme==null || !dataSetId.equals(scheme.getDataSetId())) throw error(HttpStatus.NOT_FOUND,"样式方案不存在");
        return schemeVO(scheme);
    }

    /** 外部文件仅携带分类规则，运行时分类和图层标识始终取自目标数据集。 */
    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Scheme importScheme(Long dataSetId, GisVectorRequests.Style request) {
        dataSetStyles.require(dataSetId, true);
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 100)
            throw error(HttpStatus.BAD_REQUEST, "样式方案名称无效");
        if (gisVectorStyleMapper.schemeOptions(dataSetId).stream().anyMatch(s -> s.getName().equals(request.name().trim())))
            throw error(HttpStatus.CONFLICT, "已存在同名样式方案，请修改方案名称");
        JsonNode file = request.definition();
        if (file == null || !file.isObject() || !file.path("schemaVersion").isIntegralNumber()
                || file.path("schemaVersion").decimalValue().compareTo(java.math.BigDecimal.ONE) != 0
                || !"VECTOR_STYLE_RULES".equals(file.path("kind").asText())
                || !file.path("groups").isArray() || file.path("groups").isEmpty() || file.path("groups").size() > 512
                || json.writeValueAsBytes(file).length > 8 * 1024 * 1024)
            throw error(HttpStatus.BAD_REQUEST, "请选择版本1的分类样式JSON文件，包含1至512个分类且不超过8MiB");
        for (String key : file.propertyNames())
            if (!List.of("schemaVersion", "kind", "name", "groups").contains(key))
                throw error(HttpStatus.BAD_REQUEST, "样式文件包含不支持的字段: " + key);
        if (file.has("name") && (!file.path("name").isString() || file.path("name").asText().isBlank()
                || file.path("name").asText().length() > 100))
            throw error(HttpStatus.BAD_REQUEST, "文件中的方案名称无效");

        ObjectNode config = json.valueToTree(cartography.cartography(dataSetId));
        ArrayNode styles = (ArrayNode) config.path("defaults").path("styles");
        var styleIndexes = new HashMap<String, Integer>();
        for (int i = 0; i < styles.size(); i++) styleIndexes.put(styles.get(i).path("id").asText(), i);
        var groups = gisVectorStyleMapper.groups(dataSetId);
        var importedGroups = new HashSet<Long>();
        for (JsonNode entry : file.path("groups")) {
            if (!entry.isObject() || !entry.path("name").isString() || entry.path("name").asText().isBlank()
                    || entry.path("name").asText().length() > 200 || !entry.path("enabled").isBoolean()
                    || !List.of("POINT", "LINE", "POLYGON").contains(entry.path("geometryType").asText())
                    || !entry.path("style").isObject()
                    || entry.has("override") && !entry.path("override").isObject()
                    || !entry.path("geometryType").equals(entry.path("style").path("geometryType")))
                throw error(HttpStatus.BAD_REQUEST, "分类须提供名称、几何类型、显隐及相同几何类型的完整样式");
            for (String key : entry.propertyNames())
                if (!List.of("name", "geometryType", "enabled", "style", "override").contains(key))
                    throw error(HttpStatus.BAD_REQUEST, "分类包含不支持的字段: " + key);
            String name = entry.path("name").asText().trim();
            var matches = groups.stream().filter(g -> Boolean.TRUE.equals(g.getAssignable())
                    && name.equals(g.getGroupName()) && entry.path("geometryType").asText().equals(g.getGeometryType())).toList();
            if (matches.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "当前数据集找不到分类: " + name);
            if (matches.size() != 1) throw error(HttpStatus.BAD_REQUEST, "当前数据集存在同名同类型分类，无法确定关联: " + name);
            var group = matches.getFirst();
            if (!importedGroups.add(group.getId())) throw error(HttpStatus.BAD_REQUEST, "文件中分类重复: " + name);
            String styleKey = group.getDefaultStyleKey();
            if (styleKey == null) throw error(HttpStatus.CONFLICT, "当前分类缺少默认样式: " + name);
            if (groups.stream().filter(g -> styleKey.equals(g.getDefaultStyleKey())).count() != 1)
                throw error(HttpStatus.BAD_REQUEST, "分类与其他分类共用默认样式，请先分配独立样式: " + name);
            Integer index = styleIndexes.get(styleKey);
            if (index == null) throw error(HttpStatus.CONFLICT, "当前分类缺少默认样式: " + name);
            ObjectNode importedStyle = (ObjectNode) entry.path("style").deepCopy();
            validateImportedStyle(importedStyle, name);
            importedStyle.put("id", styleKey);
            styles.set(index, importedStyle);
            ObjectNode groupConfig = (ObjectNode) config.path("groups").path(group.getGroupKey());
            groupConfig.put("enabled", entry.path("enabled").asBoolean());
            groupConfig.set("override", entry.has("override") ? entry.path("override").deepCopy() : json.createObjectNode());
        }
        // 新方案内的分类规则优先于原有图层自定义样式，不修改工作区中的图层配置。
        for (var layer : gisVectorFeatureMapper.dataSetLayers(dataSetId)) {
            if (!importedGroups.contains(layer.getGroupId())) continue;
            JsonNode layerConfig = config.path("layers").path(layer.getId().toString());
            if (layerConfig.isObject()) {
                ((ObjectNode) layerConfig).putNull("definition").put("configured", true);
            }
        }
        config.put("hasFeatureOverrides", false);
        ObjectNode definition = json.createObjectNode().put("schemaVersion", 1).put("kind", "VECTOR_STYLE_SCHEME");
        definition.set("cartography", config);
        definition.set("featureOverrides", json.createObjectNode());
        return saveScheme(dataSetId, null, null, new GisVectorRequests.Scheme(request.name(), definition, null, null, null));
    }

    /** 补充外部文件的类型检查；符号、条件和比例尺语义复用方案校验。 */
    private void validateImportedStyle(JsonNode style, String name) {
        for (String key : style.propertyNames())
            if (!List.of("geometryType", "enabled", "minZoom", "maxZoom", "renderOrder", "symbol", "labels",
                    "zoomStops", "overrides", "renderRules").contains(key))
                throw error(HttpStatus.BAD_REQUEST, "分类样式包含不支持的字段: " + name + "." + key);
        if (!style.path("enabled").isBoolean() || !style.path("minZoom").isNumber() || !style.path("maxZoom").isNumber()
                || !style.path("renderOrder").isNumber() || !Double.isFinite(style.path("renderOrder").asDouble())
                || !style.path("symbol").isObject() || !style.path("overrides").isArray())
            throw error(HttpStatus.BAD_REQUEST, "分类缺少完整样式: " + name);
        JsonNode labels = style.path("labels");
        if (!labels.isObject() || !labels.path("enabled").isBoolean() || !labels.path("minZoom").isNumber()
                || !Double.isFinite(labels.path("minZoom").asDouble()) || labels.path("minZoom").asDouble() < 0
                || labels.path("minZoom").asDouble() > 25 || !VectorTemplateRules.TILE_FIELDS.contains(labels.path("field").asText())
                || labels.hasNonNull("fallbackField") && !VectorTemplateRules.TILE_FIELDS.contains(labels.path("fallbackField").asText()))
            throw error(HttpStatus.BAD_REQUEST, "分类标注配置无效: " + name);
        for (String color : List.of("color", "strokeColor"))
            if (labels.has(color) && !labels.path(color).asText().matches("#[0-9a-fA-F]{6}"))
                throw error(HttpStatus.BAD_REQUEST, "分类标注颜色无效: " + name);
        if (labels.has("fontFamily") && (!labels.path("fontFamily").isString() || labels.path("fontFamily").asText().length() > 100)
                || labels.has("fontWeight") && !List.of("normal", "bold").contains(labels.path("fontWeight").asText()))
            throw error(HttpStatus.BAD_REQUEST, "分类标注字体无效: " + name);
        for (String field : List.of("fontSize", "strokeWidth")) {
            if (!labels.has(field)) continue;
            JsonNode value = labels.path(field);
            double min = field.equals("fontSize") ? 1 : 0, max = field.equals("fontSize") ? 64 : 12;
            if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min || value.asDouble() > max)
                throw error(HttpStatus.BAD_REQUEST, "分类标注尺寸无效: " + name);
        }
        if (labels.has("fontSizeStops")) validateImportedStops(labels.path("fontSizeStops"), 1, 64, name);
        var definitions = new java.util.ArrayList<JsonNode>();
        definitions.add(style);
        style.path("overrides").forEach(definitions::add);
        for (var definition : definitions) {
            if (!definition.has("zoomStops")) continue;
            JsonNode stops = definition.path("zoomStops");
            if (!stops.isObject()) throw error(HttpStatus.BAD_REQUEST, "层级插值须为对象: " + name);
            for (String field : stops.propertyNames()) {
                if (!List.of("width", "strokeWidth", "radius", "opacity", "strokeOpacity", "fillOpacity").contains(field))
                    throw error(HttpStatus.BAD_REQUEST, "不支持的层级插值字段: " + name + "." + field);
                validateImportedStops(stops.path(field), 0, field.endsWith("pacity") ? 1 : 12, name);
            }
        }
    }

    private void validateImportedStops(JsonNode stops, double min, double max, String name) {
        if (!stops.isArray() || stops.isEmpty() || stops.size() > 64)
            throw error(HttpStatus.BAD_REQUEST, "层级插值须包含1至64个节点: " + name);
        double previous = -1;
        for (var stop : stops) {
            if (!stop.isArray() || stop.size() != 2 || !stop.get(0).isNumber() || !stop.get(1).isNumber()
                    || !Double.isFinite(stop.get(0).asDouble()) || !Double.isFinite(stop.get(1).asDouble())
                    || stop.get(0).asDouble() < 0 || stop.get(0).asDouble() > 25 || stop.get(0).asDouble() <= previous
                    || stop.get(1).asDouble() < min || stop.get(1).asDouble() > max)
                throw error(HttpStatus.BAD_REQUEST, "层级插值节点须按层级递增且数值有效: " + name);
            previous = stop.get(0).asDouble();
        }
    }

    @Transactional(rollbackFor=Exception.class)
    public GisVectorVO.Scheme saveScheme(Long dataSetId,Long id,String ifMatch,GisVectorRequests.Scheme request) {
        var snapshot=dataSetStyles.require(dataSetId,true);
        if(request.name()==null || request.name().isBlank() || request.name().trim().length()>100) throw error(HttpStatus.BAD_REQUEST,"样式方案名称无效");
        JsonNode definition=request.definition();
        if(definition==null) {
            if (request.styleVersion() != null && !request.styleVersion().equals(snapshot.getStyleVersion()))
                throw stale();
            ObjectNode captured=json.createObjectNode().put("schemaVersion",1).put("kind","VECTOR_STYLE_SCHEME");
            captured.set("cartography",json.valueToTree(cartography.cartography(dataSetId)));
            captured.set("featureOverrides",json.readTree(gisVectorStyleMapper.allFeatureCartography(dataSetId)));
            definition=captured;
        }
        // 新方案合并当前编辑草稿，不改动工作模板或作为基础的已保存方案。
        definition = definition.deepCopy();
        if (request.groupOverrides() != null) {
            if (!request.groupOverrides().isObject()) throw error(HttpStatus.BAD_REQUEST, "分类草稿须为对象");
            for (String key : request.groupOverrides().propertyNames()) {
                JsonNode group = definition.path("cartography").path("groups").path(key);
                if (!group.isObject()) throw error(HttpStatus.BAD_REQUEST, "分类草稿不属于当前方案: " + key);
                ((ObjectNode) group).set("override", request.groupOverrides().path(key));
            }
        }
        if (request.groupVisibility() != null) {
            if (!request.groupVisibility().isObject()) throw error(HttpStatus.BAD_REQUEST, "分类显隐须为对象");
            for (String key : request.groupVisibility().propertyNames()) {
                JsonNode group = definition.path("cartography").path("groups").path(key);
                JsonNode visible = request.groupVisibility().path(key);
                if (!group.isObject() || !visible.isBoolean()) throw error(HttpStatus.BAD_REQUEST, "分类显隐无效: " + key);
                ((ObjectNode) group).put("enabled", visible.asBoolean());
            }
        }
        validateScheme(snapshot,definition);
        var scheme=new org.ocean.admin.gis.entity.GisVectorStyleScheme();
        scheme.setDataSetId(dataSetId);scheme.setName(request.name().trim());scheme.setDefinitionJson(json.writeValueAsString(definition));
        if(id==null) {
            scheme.setId(IdWorker.getId());gisVectorStyleMapper.insertScheme(scheme);
        } else {
            var existing=scheme(dataSetId,id);
            VectorDataSetStyleService.requireMatch(ifMatch,schemeEtag(id,existing.version()));
            scheme.setId(id);scheme.setVersion(existing.version());
            if(gisVectorStyleMapper.updateScheme(scheme)!=1) throw stale();
        }
        return schemeVO(gisVectorStyleMapper.scheme(scheme.getId()));
    }

    @Transactional(rollbackFor=Exception.class)
    public void deleteScheme(Long dataSetId,Long id,String ifMatch) {
        dataSetStyles.require(dataSetId,true);
        var scheme=scheme(dataSetId,id);
        VectorDataSetStyleService.requireMatch(ifMatch,schemeEtag(id,scheme.version()));
        if(gisVectorStyleMapper.deleteScheme(id,scheme.version())!=1) throw stale();
    }

    /** 发布使用同一套规则校验；方案不能修改瓦片生成策略或输出字段。 */
    public void validateScheme(org.ocean.admin.gis.entity.GisVectorDataSetStyle snapshot,JsonNode definition) {
        if(!definition.isObject() || definition.path("schemaVersion").asInt()!=1
                || !"VECTOR_STYLE_SCHEME".equals(definition.path("kind").asText())
                || !definition.path("cartography").isObject() || !definition.path("featureOverrides").isObject()
                || json.writeValueAsBytes(definition).length>8*1024*1024)
            throw error(HttpStatus.BAD_REQUEST,"样式方案结构无效或超过8MiB");
        for(String key:definition.propertyNames()) if(!List.of("schemaVersion","kind","cartography","featureOverrides").contains(key))
            throw error(HttpStatus.BAD_REQUEST,"样式方案包含不支持的字段: "+key);
        JsonNode config=definition.path("cartography");
        if(!snapshot.getDataSetId().toString().equals(config.path("dataSetId").asText())) throw error(HttpStatus.BAD_REQUEST,"样式所属数据集不一致");
        ObjectNode template=(ObjectNode)json.readTree(snapshot.getDefinitionJson());
        template.set("cartography",config.path("defaults"));
        try { templateRules.compile(template); }
        catch(IllegalArgumentException ex) { throw error(HttpStatus.BAD_REQUEST,ex.getMessage()); }
        if(!config.path("groups").isObject() || !config.path("layers").isObject()) throw error(HttpStatus.BAD_REQUEST,"缺少分类与图层显示配置");
        var groups = gisVectorStyleMapper.groups(snapshot.getDataSetId()).stream().collect(java.util.stream.Collectors.toMap(GisVectorGroup::getGroupKey, group -> group));
        for (String key : config.path("groups").propertyNames()) {
            var group = groups.get(key);
            JsonNode value = config.path("groups").path(key);
            if (group == null || !group.getId().toString().equals(value.path("id").asText()) || !value.path("enabled").isBoolean())
                throw error(HttpStatus.BAD_REQUEST, "分类配置无效: " + key);
            if (value.hasNonNull("override") && !value.path("override").isEmpty()) validateOverride(value.path("override"), group.getGeometryType());
        }
        for(String id:config.path("layers").propertyNames()) {
            JsonNode layer=config.path("layers").path(id);
            if (!id.matches("[1-9][0-9]{0,18}") || !layer.path("enabled").isBoolean()
                    || !layer.path("automatic").isBoolean() || !layer.path("configured").isBoolean())
                throw error(HttpStatus.BAD_REQUEST, "图层显示配置无效");
            if(layer.hasNonNull("definition")) {
                JsonNode validated=rules.validate(layer.path("definition"));
                for(JsonNode rule:validated.path("rules")) {
                    rules.validateTileFields(rule.path("filter"));
                    templateRules.bounds(rule.path("minZoom").asDouble(0), rule.path("maxZoom").asDouble(22));
                    rules.validateSymbolSize(rule.path("symbol"), "符号尺寸超出瓦片缓冲区允许范围");
                }
            }
        }
        for(String id:definition.path("featureOverrides").propertyNames()) {
            if(!id.matches("[1-9][0-9]{0,18}") || !definition.path("featureOverrides").path(id).isObject())
                throw error(HttpStatus.BAD_REQUEST,"要素覆盖配置无效");
            validateOverride(definition.path("featureOverrides").path(id), null);
        }
    }

    /** 独立方案中的覆盖也遵循工作区符号边界；不要求要素继续留在PostGIS。 */
    private void validateOverride(JsonNode patch, String kind) {
        if (!patch.isObject() || !patch.path("symbol").isObject() || json.writeValueAsBytes(patch).length > 16384)
            throw error(HttpStatus.BAD_REQUEST, "覆盖须提供不超过16KiB的symbol对象");
        for (String key : patch.propertyNames()) if (!List.of("schemaVersion", "symbol", "visible", "mode", "minZoom", "maxZoom", "segments", "labels").contains(key))
            throw error(HttpStatus.BAD_REQUEST, "不支持的覆盖字段: " + key);
        if (patch.has("visible") && !patch.path("visible").isBoolean()) throw error(HttpStatus.BAD_REQUEST, "visible须为布尔值");
        if (patch.has("mode")) {
            String mode = patch.path("mode").asText();
            if (!List.of("OVERALL", "SEGMENTED").contains(mode)) throw error(HttpStatus.BAD_REQUEST, "配图模式无效");
            JsonNode min = patch.path("minZoom"), max = patch.path("maxZoom");
            if (!min.isNumber() || !max.isNumber() || !Double.isFinite(min.asDouble()) || !Double.isFinite(max.asDouble()) || min.asDouble() < 0 || min.asDouble() > 24
                    || max.asDouble() > 25 || min.asDouble() >= max.asDouble()) throw error(HttpStatus.BAD_REQUEST, "显示层级无效");
            JsonNode segments = patch.path("segments");
            if (!segments.isArray() || segments.size() > 25) throw error(HttpStatus.BAD_REQUEST, "分段配置无效");
            if ("SEGMENTED".equals(mode)) {
                double start = min.asDouble();
                for (JsonNode segment : segments) {
                    if (!segment.path("minZoom").isNumber() || !segment.path("maxZoom").isNumber()
                            || segment.path("minZoom").asDouble() != start || !Double.isFinite(segment.path("maxZoom").asDouble()) || segment.path("maxZoom").asDouble() <= start
                            || segment.path("maxZoom").asDouble() > max.asDouble())
                        throw error(HttpStatus.BAD_REQUEST, "分段须连续且不能重叠");
                    validateOverride(json.createObjectNode().set("symbol", segment.path("symbol")), kind);
                    start = segment.path("maxZoom").asDouble();
                }
                if (segments.isEmpty() || start != max.asDouble()) throw error(HttpStatus.BAD_REQUEST, "分段须覆盖完整显示层级");
            }
            if (patch.has("labels") && !patch.path("labels").isNull()) {
                JsonNode labels = patch.path("labels");
                if (!labels.isObject() || !labels.path("enabled").isBoolean()
                        || !labels.path("fontFamily").isString() || labels.path("fontFamily").asText().isBlank()
                        || labels.path("fontFamily").asText().length() > 100
                        || !List.of("normal", "bold").contains(labels.path("fontWeight").asText()))
                    throw error(HttpStatus.BAD_REQUEST, "标注配置无效");
                for (String color : List.of("color", "strokeColor"))
                    if (!labels.path(color).asText().matches("#[0-9a-fA-F]{6}")) throw error(HttpStatus.BAD_REQUEST, "标注颜色无效");
                double fontSize = labels.path("fontSize").asDouble(Double.NaN);
                double strokeWidth = labels.path("strokeWidth").asDouble(Double.NaN);
                if (!labels.path("fontSize").isNumber() || !labels.path("strokeWidth").isNumber()
                        || !Double.isFinite(fontSize) || fontSize < 6 || fontSize > 36 || !Double.isFinite(strokeWidth) || strokeWidth < 0 || strokeWidth > 8)
                    throw error(HttpStatus.BAD_REQUEST, "标注字号或描边宽度无效");
            }
        } else if (patch.has("minZoom") || patch.has("maxZoom") || patch.has("segments") || patch.has("labels")) {
            throw error(HttpStatus.BAD_REQUEST, "完整配图配置须提供mode");
        }
        rules.validateSymbolSize(patch.path("symbol"), "覆盖符号尺寸不能超过12 CSS像素");
        for (String candidate : kind == null ? List.of("POINT", "LINE", "POLYGON") : List.of(kind)) {
            var defaults = json.createObjectNode();
            if ("POLYGON".equals(candidate)) defaults.put("fillColor", "#ffffff").put("fillOpacity", .5).put("strokeColor", "#000000").put("strokeWidth", 1).put("strokeOpacity", 1);
            else if ("LINE".equals(candidate)) defaults.put("color", "#000000").put("width", 1).put("opacity", 1);
            else defaults.put("color", "#000000").put("radius", 6).put("opacity", 1).put("strokeColor", "#000000").put("strokeWidth", 0);
            try { templateRules.symbol(candidate, templateRules.merge(defaults, patch.path("symbol"))); return; }
            catch (IllegalArgumentException ignored) { }
        }
        throw error(HttpStatus.BAD_REQUEST, "覆盖符号字段或类型无效");
    }

    public static String schemeEtag(Long id,long version) { return "\"scheme-"+id+"-"+version+"\""; }

    private GisVectorVO.Scheme schemeVO(org.ocean.admin.gis.entity.GisVectorStyleScheme scheme) {
        return new GisVectorVO.Scheme(scheme.getId().toString(),scheme.getDataSetId().toString(),scheme.getName(),
                scheme.getVersion(),json.readTree(scheme.getDefinitionJson()));
    }

    private ResponseStatusException stale() { return error(HttpStatus.PRECONDITION_FAILED, "样式已变化，请重新加载后编辑"); }
    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    /** 固定所选方案及默认样式，供数据集 WMS 与任务发布共用。 */

    public String publicationDefinition(Long dataSetId, List<Long> schemeIds, Long defaultSchemeId) {
        List<Long> ids = schemeIds == null || schemeIds.isEmpty()
                ? List.of(defaultSchemeId == null ? 0L : defaultSchemeId)
                : schemeIds.stream().distinct().toList();
        if (ids.isEmpty() || ids.getFirst() <= 0) {
            List<GisVectorVO.Scheme> schemes = schemes(dataSetId);
            if (schemes.isEmpty()) {
                throw new IllegalArgumentException("矢量数据集没有可用样式方案");
            }
            ids = List.of(Long.parseLong(schemes.getFirst().id()));
        }
        Long defaultId = defaultSchemeId == null
                ? ids.getFirst() : defaultSchemeId;
        if (!ids.contains(defaultId)) {
            throw new IllegalArgumentException("默认样式方案必须属于所选方案");
        }
        ObjectNode binding = json.createObjectNode()
                .put("schemaVersion", 1)
                .put("releaseVersion", java.util.UUID.randomUUID().toString())
                .put("defaultStyleId", defaultId.toString());
        ArrayNode styles = binding.putArray("styles");
        for (Long id : ids) {
            GisVectorVO.Scheme scheme = scheme(dataSetId, id);
            styles.add(json.valueToTree(scheme));
        }
        return binding.toString();
    }
}
