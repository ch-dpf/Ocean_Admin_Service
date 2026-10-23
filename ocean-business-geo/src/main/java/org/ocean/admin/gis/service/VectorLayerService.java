package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.util.GeoJsonFeatureReader;
import org.ocean.admin.gis.util.VectorTemplateRules;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** 图层查询及编辑事务；写入时锁定图层，保证要素与汇总版本一致。 */
@Service
@RequiredArgsConstructor
public class VectorLayerService {
    private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
    private final GisVectorMapper mapper;
    private final ObjectMapper json;
    private final GeoJsonFeatureReader reader;
    private final VectorTemplateRules templateRules;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<List<GisVectorVO.Layer>> page(Long dataSetId, Long groupId, Long sourceFileId, int current, int size) {
        if (current < 1 || size < 1 || size > 100 || (dataSetId != null && dataSetId < 1)) throw badRequest("页码须大于0，每页1至100条");
        if (groupId != null && (dataSetId == null || groupId < 1 || mapper.group(dataSetId, groupId) == null))
            throw badRequest("分组查询须指定正确的数据集及分组");
        if (sourceFileId != null && sourceFileId < 1) throw badRequest("来源文件ID无效");
        Page<GisVectorLayer> page = mapper.pageLayers(new Page<>(current, size), dataSetId, groupId, sourceFileId);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords().stream().map(this::toVO).toList());
    }

    public GisVectorVO.Layer detail(Long id) { return toVO(requireLayer(id, false)); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.FeatureCollection features(Long layerId, String bbox, int limit, String cursor) {
        if (limit < 1 || limit > 1000) throw badRequest("limit 须为1至1000");
        GisVectorLayer layer = ready(layerId, false);
        Double[] box = parseBox(bbox);
        String queryKey = Arrays.toString(box);
        long after = decodeCursor(cursor, layer, queryKey);
        List<GisVectorFeature> rows = mapper.features(layerId, after, limit + 1, box[0], box[1], box[2], box[3]);
        List<GisVectorVO.Feature> features = new ArrayList<>();
        long bytes = 0;
        boolean more = false;
        long last = after;
        for (GisVectorFeature row : rows) {
            long rowBytes = (row.getGeometryJson() == null ? 0 : row.getGeometryJson().getBytes(StandardCharsets.UTF_8).length)
                    + (row.getPropertiesJson() == null ? 4 : row.getPropertiesJson().getBytes(StandardCharsets.UTF_8).length) + 256L;
            if (features.size() == limit || (bytes + rowBytes > MAX_PAGE_BYTES && !features.isEmpty())) { more = true; break; }
            features.add(toFeature(row));
            bytes += rowBytes;
            last = row.getId();
        }
        String next = more ? encodeCursor(layer, last, queryKey) : null;
        return new GisVectorVO.FeatureCollection("FeatureCollection", features, features.size(), more, next, layer.getDataVersion());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.Feature feature(Long layerId, Long id) {
        ready(layerId, false);
        return toFeature(requireFeature(layerId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Feature createFeature(Long layerId, JsonNode body) {
        GisVectorLayer layer = ready(layerId, true);
        GisVectorFeature feature = parsedFeature(layerId, body);
        requireKind(layer, body);
        feature.setId(IdWorker.getId());
        validateTopology(feature);
        mapper.insertFeatures(List.of(feature));
        mapper.refreshLayer(layerId);
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, false);
        return toFeature(requireFeature(layerId, feature.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Feature updateFeature(Long layerId, Long id, String ifMatch, JsonNode body) {
        GisVectorLayer layer = ready(layerId, true);
        GisVectorFeature existing = requireFeature(layerId, id);
        requireMatch(ifMatch, featureEtag(id, existing.getVersion()));
        if (body != null && body.has("id") && !id.toString().equals(body.get("id").asText())) throw badRequest("Feature.id 与路径不一致");
        GisVectorFeature feature = parsedFeature(layerId, body);
        requireKind(layer, body);
        feature.setId(id);
        // XYZ 编辑须显式提交高程，防止二维拖动误删已有 Z。
        JsonNode oldGeometry = json.readTree(existing.getGeometryJson());
        if (coordinateDimension(oldGeometry) != coordinateDimension(body.get("geometry"))) throw badRequest("编辑须保留原要素的 XY/XYZ 坐标维度");
        validateTopology(feature);
        if (mapper.updateFeature(feature, existing.getVersion()) != 1) throw preconditionFailed();
        mapper.refreshLayer(layerId);
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, false);
        return toFeature(requireFeature(layerId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFeature(Long layerId, Long id, String ifMatch) {
        GisVectorLayer layer = ready(layerId, true);
        GisVectorFeature existing = requireFeature(layerId, id);
        requireMatch(ifMatch, featureEtag(id, existing.getVersion()));
        if (mapper.deleteFeature(layerId, id, existing.getVersion()) != 1) throw preconditionFailed();
        mapper.refreshLayer(layerId);
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteLayer(Long id, String ifMatch) {
        GisVectorLayer layer = requireLayer(id, true);
        if ("IMPORTING".equals(layer.getLayerStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "正在导入的图层不能删除");
        requireMatch(ifMatch, layerEtag(id, layer.getDataVersion(), layer.getPresentationVersion()));
        mapper.deleteLayer(id);
        mapper.bumpDataSetRevision(layer.getDataSetId(), true, true);
    }

    public GisVectorVO.Layer toVO(GisVectorLayer layer) {
        return new GisVectorVO.Layer(layer.getId().toString(), layer.getDataSetId().toString(),
                layer.getSourceFileId() == null ? null : layer.getSourceFileId().toString(),
                layer.getLayerKey(), layer.getLayerName(), layer.getLayerStatus(), layer.getFeatureCount(),
                tree(layer.getGeographicExtent()), tree(layer.getGeometryTypes()), layer.getDataVersion(), layer.getCreateTime(), layer.getUpdateTime(),
                layer.getGroupId().toString(), layer.getOriginalGroupId().toString(), layer.getGeometryType(), layer.getAssignmentMode(),
                Boolean.TRUE.equals(layer.getEnabled()), layer.getStyleMode(), layer.getPresentationVersion());
    }

    public static String featureEtag(Long id, long version) { return "\"feature-" + id + "-v" + version + "\""; }
    public static String layerEtag(Long id, long version, long presentation) { return "\"layer-" + id + "-d" + version + "-p" + presentation + "\""; }

    private GisVectorLayer ready(Long id, boolean lock) {
        GisVectorLayer layer = requireLayer(id, lock);
        if (!"READY".equals(layer.getLayerStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "图层尚未完成导入");
        return layer;
    }

    private GisVectorLayer requireLayer(Long id, boolean lock) {
        if (id == null || id < 1) throw badRequest("图层ID须大于0");
        if (lock) {
            var existing = mapper.layer(id);
            if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图层不存在或已删除");
            if (mapper.lockDataSetStyle(existing.getDataSetId()) == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "数据集尚未初始化样式");
        }
        GisVectorLayer layer = lock ? mapper.lockLayer(id) : mapper.layer(id);
        if (layer == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图层不存在或已删除");
        if (layer.getGroupId() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "存量图层尚未分类，须单独迁移");
        return layer;
    }

    private void requireKind(GisVectorLayer layer, JsonNode body) {
        try {
            if (!layer.getGeometryType().equals(templateRules.kind(body.get("geometry")))) throw badRequest("几何类别与图层不一致");
        } catch (IllegalArgumentException ex) { throw badRequest(ex.getMessage()); }
    }

    private GisVectorFeature requireFeature(Long layerId, Long id) {
        if (id == null || id < 1) throw badRequest("要素ID须大于0");
        GisVectorFeature feature = mapper.feature(layerId, id);
        if (feature == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图层内不存在该要素");
        return feature;
    }

    private GisVectorFeature parsedFeature(Long layerId, JsonNode body) {
        try {
            var parsed = reader.parseFeature(body, 0);
            GisVectorFeature feature = new GisVectorFeature();
            feature.setLayerId(layerId);
            feature.setGeometryJson(parsed.geometry());
            feature.setPropertiesJson(parsed.properties());
            return feature;
        } catch (IllegalArgumentException ex) { throw badRequest(ex.getMessage()); }
    }

    private void validateTopology(GisVectorFeature feature) {
        var error = mapper.firstGeometryError(List.of(feature));
        if (error != null) throw badRequest("无效几何: " + error.reason());
    }

    private GisVectorVO.Feature toFeature(GisVectorFeature feature) {
        return new GisVectorVO.Feature("Feature", feature.getId().toString(), tree(feature.getGeometryJson()),
                tree(feature.getPropertiesJson()), feature.getVersion(), feature.getLayerId().toString(),
                feature.getSourceFeatureIndex() == null ? null : new GisVectorVO.SourceReference(feature.getSourceFeatureIndex(),
                        tree(feature.getSourceFeatureId()), feature.getSourceGeometryPath(), feature.getClassificationRuleKey()));
    }

    private JsonNode tree(String value) { return value == null ? null : json.readTree(value); }

    private void requireMatch(String value, String expected) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "编辑或删除必须携带详情响应中的 If-Match/ETag");
        if (!expected.equals(value.trim())) throw preconditionFailed();
    }

    private ResponseStatusException preconditionFailed() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "数据版本已变化，请重新加载后编辑");
    }

    private Double[] parseBox(String value) {
        if (value == null) throw badRequest("属性分页查询必须指定bbox，地图显示请使用数据集瓦片");
        try {
            String[] parts = value.split(",", -1);
            if (parts.length != 4) throw new IllegalArgumentException();
            Double[] box = new Double[4];
            for (int i = 0; i < 4; i++) {
                box[i] = Double.parseDouble(parts[i]);
                if (!Double.isFinite(box[i])) throw new IllegalArgumentException();
            }
            if (Math.abs(box[0]) > 180 || Math.abs(box[2]) > 180 || Math.abs(box[1]) > 90
                    || Math.abs(box[3]) > 90 || box[1] >= box[3] || box[0].doubleValue() == box[2].doubleValue()) throw new IllegalArgumentException();
            return box;
        } catch (IllegalArgumentException ex) { throw badRequest("bbox 须为 west,south,east,north；west>east 表示跨日期变更线"); }
    }

    private String encodeCursor(GisVectorLayer layer, long lastId, String queryKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (layer.getId() + ":" + layer.getDataVersion() + ":" + lastId + ":" + queryKey).getBytes(StandardCharsets.UTF_8));
    }

    private long decodeCursor(String value, GisVectorLayer layer, String queryKey) {
        if (value == null) return 0;
        if (value.length() > 1024) throw badRequest("cursor 无效");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":", 4);
            if (parts.length != 4 || Long.parseLong(parts[0]) != layer.getId() || !queryKey.equals(parts[3])) throw new IllegalArgumentException();
            if (Long.parseLong(parts[1]) != layer.getDataVersion()) throw new ResponseStatusException(HttpStatus.CONFLICT, "图层已编辑，请从首页重新查询");
            long after = Long.parseLong(parts[2]);
            if (after < 1) throw new IllegalArgumentException();
            return after;
        } catch (IllegalArgumentException ex) { throw badRequest("cursor 与图层或查询范围不匹配"); }
    }

    private int coordinateDimension(JsonNode geometry) {
        if ("GeometryCollection".equals(geometry.path("type").asText())) return coordinateDimension(geometry.path("geometries").get(0));
        JsonNode position = geometry.get("coordinates");
        while (position.get(0).isArray()) position = position.get(0);
        return position.size();
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
