package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.util.VectorTemplateRules;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 矢量数据集样式服务
 * 数据集级模板快照、分类目录和版本基线
 */
@Service
@RequiredArgsConstructor
public class VectorDataSetStyleService {
    private final GisVectorMapper mapper;
    private final ObjectMapper json;
    private final VectorTemplateRules rules;
    private final Map<String, VectorTemplateRules.Compiled> compiled = new ConcurrentHashMap<>();

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(Long dataSetId) {
        byte[] bytes;
        try (var input = new ClassPathResource("vector/style.json").getInputStream()) { bytes = input.readAllBytes(); }
        catch (IOException ex) { throw new IllegalStateException("默认矢量模板不可读，数据集未创建", ex); }
        var definition = json.readTree(bytes);
        rules.compile(definition);
        var snapshot = new GisVectorDataSetStyle();
        snapshot.setId(IdWorker.getId());
        snapshot.setDataSetId(dataSetId);
        snapshot.setTemplateId(definition.path("templateId").asText());
        snapshot.setTemplateVersion(definition.path("templateVersion").asInt());
        snapshot.setSchemaVersion(3);
        snapshot.setCartographyJson(json.writeValueAsString(definition.path("cartography")));
        ((tools.jackson.databind.node.ObjectNode) definition).remove("cartography");
        snapshot.setDefinitionJson(json.writeValueAsString(definition));
        snapshot.setTemplateChecksum(digest(bytes));
        if (mapper.insertDataSetStyle(snapshot) != 1) throw new IllegalStateException("样式快照创建失败");
        Map<String, Long> ids = new HashMap<>();
        for (var g : definition.path("groups")) ids.put(g.path("id").asText(), IdWorker.getId());
        List<GisVectorGroup> groups = new ArrayList<>();
        for (var node : definition.path("groups")) {
            var group = new GisVectorGroup();
            group.setId(ids.get(node.path("id").asText()));
            group.setDataSetId(dataSetId);
            group.setDatasetStyleId(snapshot.getId());
            group.setParentId(ids.get(node.path("parentId").asText(null)));
            group.setGroupKey(node.path("id").asText());
            group.setGroupName(node.path("name").asText());
            group.setNodeType(node.path("nodeType").asText());
            group.setGeometryType(node.path("geometryType").asText(null));
            group.setAssignable(node.path("assignable").asBoolean());
            group.setDefaultStyleKey(node.path("defaultStyleId").asText(null));
            group.setEnabled(definition.path("initialization").path("defaultGroupEnabled").asBoolean());
            group.setSortOrder(node.path("sortOrder").asInt());
            groups.add(group);
        }
        if (mapper.insertGroups(groups) != groups.size()) throw new IllegalStateException("默认分类目录创建失败");
    }

    public GisVectorDataSetStyle require(Long dataSetId, boolean lock) {
        if (dataSetId == null || dataSetId < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据集ID无效");
        var s = lock ? mapper.lockDataSetStyle(dataSetId) : mapper.dataSetStyle(dataSetId);
        if (s == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "矢量数据集缺少样式快照或不可用；存量初始化须单独执行");
        return s;
    }

    public VectorTemplateRules.Compiled compile(GisVectorDataSetStyle snapshot) {
        if (snapshot.getSchemaVersion() != 3) throw new ResponseStatusException(HttpStatus.CONFLICT,"该数据集使用旧分类协议，请保留旧数据并重新导入新数据集");
        // 模板以内容校验和共享编译结果，不缓存任何数据集的运行时分组ID、开关或图层状态。
        if (compiled.size() > 128) compiled.clear();
        return compiled.computeIfAbsent(snapshot.getTemplateChecksum(), k -> {
            var definition = (tools.jackson.databind.node.ObjectNode) json.readTree(snapshot.getDefinitionJson());
            definition.set("cartography", json.readTree(snapshot.getCartographyJson()));
            return rules.compile(definition);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.DataSetStyle detail(Long dataSetId) {
        var s = require(dataSetId, false);
        return new GisVectorVO.DataSetStyle(s.getId().toString(), dataSetId.toString(), s.getTemplateId(),
                s.getTemplateVersion(), s.getStyleVersion(), s.getDataRevision(), s.getDisplayRevision(),
                json.readTree(s.getDefinitionJson()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.GroupTree groupTree(Long dataSetId) {
        var snapshot = require(dataSetId, false);
        return new GisVectorVO.GroupTree(children(mapper.groups(dataSetId), null), groupsEtag(snapshot));
    }
    private List<GisVectorVO.Group> children(List<GisVectorGroup> groups, Long parent) {
        return groups.stream().filter(g -> Objects.equals(parent, g.getParentId())).map(g -> {
            List<GisVectorVO.Group> children = children(groups, g.getId());
            return new GisVectorVO.Group(g.getId().toString(), g.getParentId() == null ? null : g.getParentId().toString(),
                    g.getGroupKey(), g.getGroupName(), g.getNodeType(), g.getGeometryType(), g.getAssignable(),
                    g.getDefaultStyleKey(), g.getEnabled(), g.getVersion(),
                    g.getLayerCount() + children.stream().mapToLong(GisVectorVO.Group::layerCount).sum(),
                    g.getFeatureCount() + children.stream().mapToLong(GisVectorVO.Group::featureCount).sum(), children);
        }).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long dataSetId, Long groupId, boolean enabled, String ifMatch) {
        var snapshot = require(dataSetId, true);
        requireMatch(ifMatch, groupsEtag(snapshot));
        if (groupId == null || mapper.group(dataSetId, groupId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前数据集中不存在该分组");
        if (mapper.setGroupEnabled(groupId, enabled) != 1) throw new IllegalStateException("分组更新失败");
        mapper.bumpDataSetRevision(dataSetId, false, true);
    }

    public static String groupsEtag(GisVectorDataSetStyle s) { return "\"groups-" + s.getDataSetId() + "-d" + s.getDataRevision() + "-p" + s.getDisplayRevision() + "\""; }
    public static void requireMatch(String actual, String expected) {
        if (actual == null || actual.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "须携带最新If-Match");
        if (!expected.equals(actual.trim())) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "配置已变化，请重新读取");
    }
    private String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
