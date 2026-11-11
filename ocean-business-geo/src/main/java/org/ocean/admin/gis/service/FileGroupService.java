package org.ocean.admin.gis.service;

import org.ocean.admin.gis.mapper.GisVectorResourceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileGroupMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisFileGroupVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** 文件组查询、读取配置与解析生命周期；接入识别由组装服务负责。 */
@Service
@RequiredArgsConstructor
public class FileGroupService {
    private final GisFileGroupMapper groups;
    private final GisFileMetaMapper files;
    private final GisDataSetMapper dataSets;
    private final GisProcessingInputMapper processingInputs;
    private final GisVectorResourceMapper gisVectorResourceMapper;
    private final FileUploadUtil uploads;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final FileFormatService formats;

    @Transactional(readOnly = true)
    public GisFileGroup getRequired(Long groupId) {
        if (groupId == null || groupId < 1) throw new IllegalArgumentException("文件组ID无效");
        GisFileGroup group = groups.selectById(groupId);
        if (group == null) throw new IllegalArgumentException("文件组不存在或已删除");
        return group;
    }

    public List<GisFileMeta> members(Long groupId) { return groups.members(groupId); }

    /** 先锁组再锁入口，领取版本和输入修订必须同时有效。 */
    public GisFileGroup startExtraction(GisFileMeta claimedEntry) {
        return transactions.execute(status -> {
            if (claimedEntry.getFileGroupId() == null) return null;
            GisFileGroup group = groups.lock(claimedEntry.getFileGroupId());
            if (group == null || !"COMPLETE".equals(group.getAssemblyStatus())
                    || !Objects.equals(group.getEntryFileId(), claimedEntry.getId())
                    || !Set.of("QUEUED", "RUNNING").contains(group.getParseStatus())) return null;
            GisFileMeta current = files.selectActiveByIdForUpdate(claimedEntry.getId());
            if (current == null || !Objects.equals(current.getMetadataVersion(), claimedEntry.getMetadataVersion())
                    || !"RUNNING".equals(current.getMetadataStatus())) return null;
            group.setParseStatus("RUNNING");
            groups.updateState(group);
            return group;
        });
    }

    /** 必须由调用者置于最终结果提交事务，行锁保持到提交结束。 */
    public boolean lockCurrent(GisFileGroup snapshot) {
        if (snapshot == null) return false;
        GisFileGroup current = groups.lock(snapshot.getId());
        return current != null && Objects.equals(current.getInputRevision(), snapshot.getInputRevision())
                && Objects.equals(current.getEntryFileId(), snapshot.getEntryFileId())
                && "COMPLETE".equals(current.getAssemblyStatus()) && "RUNNING".equals(current.getParseStatus());
    }

    public void finishExtraction(GisFileGroup snapshot, String metadataStatus, String errorCode, String errorMessage) {
        transactions.executeWithoutResult(status -> {
            if (!lockCurrent(snapshot)) return;
            GisFileGroup current = groups.lock(snapshot.getId());
            boolean blocked = errorCode != null && Set.of("MISSING_CRS", "INPUT_OPTIONS_REQUIRED", "GDAL_UNAVAILABLE",
                    "GDAL_DRIVER_UNAVAILABLE", "DRIVER_UNAVAILABLE", "MISSING_GEOLOCATION", "MISSING_COORDINATE_FIELDS",
                    "MISSING_REQUIRED_MEMBERS", "SOURCE_UNAVAILABLE", "SOURCE_CHANGED").contains(errorCode);
            setState(current, blocked ? "BLOCKED" : "COMPLETE", blocked ? "PARKED" : metadataStatus, errorCode, errorMessage);
            if ("SOURCE_CHANGED".equals(errorCode) || "SOURCE_UNAVAILABLE".equals(errorCode)) current.setFingerprint(null);
            groups.updateState(current);
        });
    }

    @Transactional(readOnly = true)
    public GisFileGroupVO detail(Long id) {
        GisFileGroup group = getRequired(id);
        attachGeometryQuality(List.of(group));
        return new GisFileGroupVO(GisFileGroupVO.Group.from(group), group.getDuplicateOfGroupId() == null ? id : group.getDuplicateOfGroupId(), members(id));
    }

    @Transactional(readOnly = true)
    public PageResult<List<GisFileGroupVO.Group>> page(GisFileGroupVO.Query query) {
        long current = query.current() == null ? 1 : query.current();
        long size = query.size() == null ? 20 : query.size();
        if (current < 1 || size < 1 || size > 200) throw new IllegalArgumentException("分页范围无效");
        var where = new LambdaQueryWrapper<GisFileGroup>()
                .eq(query.dataSetId() != null, GisFileGroup::getDataSetId, query.dataSetId())
                .like(query.name() != null && !query.name().isBlank(), GisFileGroup::getName, query.name())
                .eq(query.format() != null, GisFileGroup::getFormat, query.format())
                .eq(query.assemblyStatus() != null, GisFileGroup::getAssemblyStatus, query.assemblyStatus())
                .eq(query.parseStatus() != null, GisFileGroup::getParseStatus, query.parseStatus())
                .ne(!Boolean.TRUE.equals(query.includeDuplicates()), GisFileGroup::getAssemblyStatus, "DUPLICATE")
                .orderByDesc(GisFileGroup::getCreateTime).orderByDesc(GisFileGroup::getId);
        Page<GisFileGroup> page = groups.selectPage(new Page<>(current, size), where);
        attachGeometryQuality(page.getRecords());
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords().stream().map(GisFileGroupVO.Group::from).toList());
    }

    private void attachGeometryQuality(List<GisFileGroup> records) {
        Map<Long, GisFileGroup> completed = new LinkedHashMap<>();
        for (var group : records) {
            if ("COMPLETED".equals(group.getParseStatus()) && group.getEntryFileId() != null
                    && !"DUPLICATE".equals(group.getAssemblyStatus())) completed.put(group.getEntryFileId(), group);
        }
        if (completed.isEmpty()) return;
        for (var result : groups.geometryQuality(List.copyOf(completed.keySet()))) {
            completed.get(result.fileMetaId()).setGeometryQuality(json.readTree(result.qualityJson()));
        }
    }

    public void updateOptions(Long id, GisFileGroupVO.Options options) {
        transactions.executeWithoutResult(status -> {
            GisFileGroup group = requireLocked(id);
            ensureIdle(group);
            if ("DUPLICATE".equals(group.getAssemblyStatus())) throw new IllegalStateException("重复记录请通过effectiveGroupId配置规范组");
            Map<String, String> normalized = new TreeMap<>();
            var saved = json.readTree(group.getReadOptionsJson());
            for (String field : List.of("sourceCrs", "xField", "yField")) {
                String value = saved.path(field).asText("");
                if (!value.isBlank()) normalized.put(field, value);
            }
            // 未提供的字段保持原值；显式空白字符串用于清除对应读取参数。
            Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put("sourceCrs", options.sourceCrs());
            supplied.put("xField", options.xField());
            supplied.put("yField", options.yField());
            for (var field : supplied.entrySet()) {
                if (field.getValue() == null) continue;
                if (field.getValue().isBlank()) normalized.remove(field.getKey());
                else normalized.put(field.getKey(), field.getValue().trim());
            }
            String sourceCrs = normalized.get("sourceCrs");
            if (sourceCrs != null && sourceCrs.matches("(?i)EPSG:[1-9][0-9]*|OGC:CRS84"))
                normalized.put("sourceCrs", sourceCrs.toUpperCase(Locale.ROOT));
            String validation = formats.validateReadOptions(GisFileFormat.valueOf(group.getFormat()), json.valueToTree(normalized), false);
            if (validation != null) throw new IllegalArgumentException(validation);
            String encoded = json.writeValueAsString(normalized);
            if (json.readTree(encoded).equals(json.readTree(group.getReadOptionsJson()))) return;
            group.setReadOptionsJson(encoded);
            group.setInputRevision(group.getInputRevision() + 1);
            if (hasSuccessfulOutput(group, members(id))) {
                setState(group, "COMPLETE", "REIMPORT_REQUIRED", "INPUT_CHANGED", "读取配置已变化，请显式重导；已有成果保持原样");
                groups.updateState(group);
                return;
            }
            if (group.getFingerprint() == null || group.getEntryFileId() == null) {
                groups.updateState(group);
                return;
            }
            queueIfReady(group);
        });
    }

    public void retry(Long id) {
        transactions.executeWithoutResult(status -> {
            GisFileGroup group = requireLocked(id);
            ensureIdle(group);
            if (hasVectorReferences(members(id))) throw new IllegalStateException("组成员仍被矢量成果引用，请显式删除相关成果后重导");
            queueIfReady(group);
        });
    }

    private void queueIfReady(GisFileGroup group) {
        if (Set.of("SOURCE_CHANGED", "SOURCE_UNAVAILABLE").contains(Objects.toString(group.getErrorCode(), "")))
            throw new IllegalStateException("原位源文件已变化或不可用，请保留源文件、删除本组登记后重新接入");
        if (group.getEntryFileId() == null || group.getFingerprint() == null || "DUPLICATE".equals(group.getAssemblyStatus()))
            throw new IllegalStateException("文件组尚未完整或已匹配其他完整组");
        String unavailable = formats.availabilityError(GisFileFormat.valueOf(group.getFormat()));
        if (unavailable != null) {
            setState(group, "BLOCKED", "PARKED", "DRIVER_UNAVAILABLE", unavailable);
            groups.updateState(group);
            return;
        }
        String error = optionsError(GisFileFormat.valueOf(group.getFormat()), group.getReadOptionsJson());
        if (error != null) {
            setState(group, "BLOCKED", "PARKED", "INPUT_OPTIONS_REQUIRED", error);
            groups.updateState(group);
            return;
        }
        groups.clearEntryMetadata(group.getEntryFileId());
        if (groups.queueEntry(group.getEntryFileId()) != 1) throw new IllegalStateException("文件组入口无法重新解析");
        setState(group, "COMPLETE", "QUEUED", null, null);
        groups.updateState(group);
        events.publishEvent(new FileSpatialMetadataService.MetadataReady(List.of(group.getEntryFileId())));
    }

    /** 旧物理文件删除接口在完成成员删除后调用，禁止留下可复用的陈旧指纹。 */
    public void invalidateMember(Long groupId, Long fileId) {
        GisFileGroup group = groups.lock(groupId);
        if (group == null) return;
        boolean hadSuccess = hasSuccessfulOutput(group, members(groupId));
        group.setInputRevision(group.getInputRevision() + 1);
        group.setFingerprint(null);
        group.setDuplicateOfGroupId(null);
        group.setMatchedRevision(null);
        if (Objects.equals(group.getEntryFileId(), fileId)) group.setEntryFileId(null);
        setState(group, "INCOMPLETE", hadSuccess ? "REIMPORT_REQUIRED" : "PARKED", "MEMBER_REMOVED", "组成员已删除，需要重新检查输入");
        groups.updateState(group);
        parkMembers(members(groupId), group);
    }

    public void delete(Long id) {
        transactions.executeWithoutResult(status -> {
            GisFileGroup observed = getRequired(id);
            if (dataSets.selectActiveByIdForUpdate(observed.getDataSetId()) == null) throw new IllegalStateException("数据集已删除");
            GisFileGroup group = requireLocked(id);
            ensureIdle(group);
            if (groups.selectCount(new LambdaQueryWrapper<GisFileGroup>()
                    .eq(GisFileGroup::getDuplicateOfGroupId, id)) > 0)
                throw new IllegalStateException("文件组仍被重复输入追溯记录引用，请先删除这些重复追溯记录");
            List<GisFileMeta> members = members(id).stream().sorted(Comparator.comparing(GisFileMeta::getId)).toList();
            for (GisFileMeta file : members) {
                files.selectActiveByIdForUpdate(file.getId());
                if (!processingInputs.selectActiveByFileMetaId(file.getId()).isEmpty() || gisVectorResourceMapper.countSourceReferences(file.getId()) > 0)
                    throw new IllegalStateException("组成员仍被处理任务或矢量成果引用，暂不能删除");
                if (Set.of("WAITING", "RUNNING").contains(file.getMetadataStatus())) throw new IllegalStateException("组成员仍在接入或解析");
                if ("UPLOAD".equals(file.getSourceType()) && !"LOCAL".equals(file.getStorageType()))
                    throw new IllegalStateException("暂不支持删除非本地托管文件");
            }
            uploads.stageStoredForTransactionalDeletion(members.stream().filter(file -> "UPLOAD".equals(file.getSourceType()))
                    .map(GisFileMeta::getStorageKey).filter(Objects::nonNull).toList());
            for (GisFileMeta file : members) {
                processingInputs.detachTerminalByFileMetaId(file.getId());
                files.physicallyDeleteActiveById(file.getId());
            }
            groups.physicallyDelete(id);
            long size = members.stream().filter(file -> "READY".equals(file.getFileStatus())).mapToLong(GisFileMeta::getSizeBytes).sum();
            int count = (int) members.stream().filter(file -> "READY".equals(file.getFileStatus())).count();
            if (dataSets.adjustFileStatistics(group.getDataSetId(), -count, -size) != 1) throw new IllegalStateException("数据集文件统计更新失败");
            events.publishEvent(new FileSpatialMetadataService.MetadataProgress(members.stream().map(GisFileMeta::getId).toList(), false));
        });
    }

    private GisFileGroup requireLocked(Long id) {
        GisFileGroup group = groups.lock(id);
        if (group == null) throw new IllegalArgumentException("文件组不存在或已删除");
        return group;
    }

    private void ensureIdle(GisFileGroup group) {
        if (Set.of("QUEUED", "RUNNING").contains(group.getParseStatus())) throw new IllegalStateException("文件组正在解析，请等待结束");
    }

    private boolean hasVectorReferences(List<GisFileMeta> members) {
        return members.stream().anyMatch(file -> gisVectorResourceMapper.countSourceReferences(file.getId()) > 0);
    }
    /** 组装与管理入口共用的组状态规则，仅在本业务包内使用。 */

    boolean hasSuccessfulOutput(GisFileGroup group, List<GisFileMeta> members) {
        return Set.of("COMPLETED", "REIMPORT_REQUIRED").contains(group.getParseStatus())
                || members.stream().anyMatch(file -> "COMPLETED".equals(file.getMetadataStatus())) || hasVectorReferences(members);
    }
    /** 组装与管理入口共用的组状态规则，仅在本业务包内使用。 */

    void parkMembers(List<GisFileMeta> members, GisFileGroup group) {
        for (GisFileMeta file : members) groups.park(file.getId(), group.getErrorCode(), group.getErrorMessage());
    }
    /** 组装与管理入口共用的组状态规则，仅在本业务包内使用。 */

    static void setState(GisFileGroup group, String assembly, String parse, String code, String message) {
        group.setAssemblyStatus(assembly);
        group.setParseStatus(parse);
        group.setErrorCode(code);
        group.setErrorMessage(message == null ? null : message.substring(0, Math.min(message.length(), 1000)));
    }
    /** 组装与管理入口共用的组状态规则，仅在本业务包内使用。 */

    String optionsError(GisFileFormat format, String options) {
        return formats.validateReadOptions(format, json.readTree(options == null ? "{}" : options), true);
    }
}
