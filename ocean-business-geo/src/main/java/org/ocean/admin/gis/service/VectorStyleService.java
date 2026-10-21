package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.entity.GisVectorStyle;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.util.VectorStyleRules;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void createDefault(Long layerId) {
        GisVectorStyle style = new GisVectorStyle();
        style.setId(IdWorker.getId());
        style.setLayerId(layerId);
        style.setName("默认样式");
        style.setIsDefault(true);
        style.setDefinitionJson(json.writeValueAsString(rules.defaultDefinition()));
        if (mapper.insertStyle(style) != 1) throw new IllegalStateException("默认样式创建失败");
    }

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
    public GisVectorVO.Style defaultStyle(Long layerId) {
        requireLayer(layerId, false);
        var style = mapper.defaultStyle(layerId);
        if (style == null) throw error(HttpStatus.NOT_FOUND, "图层默认样式不存在");
        return toVO(style);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Style create(Long layerId, GisVectorRequests.Style request) {
        GisVectorStyle style = parse(layerId, request);
        requireLayer(layerId, true);
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
        requireLayer(layerId, true);
        GisVectorStyle existing = requireStyle(layerId, id);
        requireMatch(ifMatch, existing);
        requireUniqueName(mapper.styles(layerId), updated.getName(), id);
        updated.setId(id);
        if (mapper.updateStyle(updated, existing.getVersion()) != 1) throw stale();
        return toVO(requireStyle(layerId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.Style setDefault(Long layerId, Long id, String ifMatch) {
        requireLayer(layerId, true);
        GisVectorStyle style = requireStyle(layerId, id);
        requireMatch(ifMatch, style);
        if (!style.getIsDefault()) {
            if (mapper.clearDefaultStyle(layerId) != 1) throw error(HttpStatus.CONFLICT, "图层默认样式状态不一致");
            if (mapper.setDefaultStyle(layerId, id, style.getVersion()) != 1) throw stale();
        }
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

    private void requireLayer(Long id, boolean lock) {
        if (id == null || id <= 0) throw error(HttpStatus.BAD_REQUEST, "图层ID须大于0");
        if ((lock ? mapper.lockLayer(id) : mapper.layer(id)) == null) throw error(HttpStatus.NOT_FOUND, "图层不存在或已删除");
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
