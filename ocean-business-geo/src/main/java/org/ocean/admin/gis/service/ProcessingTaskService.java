package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileGroupMapper;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.processing.input.ManagedFileInputResolver.GroupSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/** 在单一事务中创建任务、处理作业、输入快照和唯一瓦片集。 */
@Service
@RequiredArgsConstructor
public class ProcessingTaskService {
    private final ProcessingTaskRecordService taskService;
    private final GisProcessingInputMapper inputMapper;
    private final GisFileMetaMapper fileMetaMapper;
    private final GisFileGroupMapper fileGroupMapper;
    private final GisDataSetMapper dataSetMapper;
    private final GisTileSetMapper tileSetMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public CreatedProcessingTask create(String taskNo, GisSubmitProcessingCommand command,
            GisProcessingWorkspace workspace, List<GisProcessingInput> inputs) {
        lockManagedInputs(command, inputs);
        LocalDateTime now = LocalDateTime.now();
        String parametersJson = writeParameters(command);
        GisProcessingTask task = createQueuedTask(
                taskNo, command.taskName().trim(), inputs.size(), now);
        task.setProcessingType(command.processingType());
        task.setSourceType(command.sourceType());
        task.setParametersJson(parametersJson);
        task.setParameterSchemaVersion(6);
        task.setRequestFingerprint(fingerprint(command, inputs, parametersJson));
        taskService.insert(task);

        for (GisProcessingInput input : inputs) {
            input.setTaskId(task.getId());
            input.setCreateTime(now);
            input.setUpdateTime(now);
            if (inputMapper.insert(input) != 1) {
                throw new IllegalStateException("GIS处理输入创建失败: " + input.getSequenceNo());
            }
        }

        GisTileSet tileSet = new GisTileSet();
        tileSet.setTaskId(task.getId());
        tileSet.setTileType(command.processingType());
        tileSet.setTileSetStatus("BUILDING");
        tileSet.setOutputKey(workspace.outputKey());
        tileSet.setTargetCrs(command.parameters().targetCrs());
        tileSet.setTileProfile(command.parameters().tileProfile());
        tileSet.setOutputFormat(command.parameters().outputFormat());
        tileSet.setMinZoom(command.parameters().minZoom());
        tileSet.setMaxZoom(command.parameters().maxZoom());
        tileSet.setManifestKey(workspace.outputKey() + "/tiles/"
                + (command.processingType() == GisProcessingType.TERRAIN
                        ? "layer.json" : "manifest.json"));
        tileSet.setCreateTime(now);
        tileSet.setUpdateTime(now);
        if (tileSetMapper.insert(tileSet) != 1) {
            throw new IllegalStateException("GIS瓦片集创建失败");
        }
        return new CreatedProcessingTask(task.getId(), tileSet.getId());
    }

    /** 按数据集、文件组、成员文件的顺序锁定来源，关闭提交快照与活动引用之间的竞态窗口。 */
    private void lockManagedInputs(
            GisSubmitProcessingCommand command, List<GisProcessingInput> inputs) {
        if (command.sourceType() != GisInputSourceType.MANAGED_FILE) {
            return;
        }
        Map<Long, GroupSnapshot> snapshots = new LinkedHashMap<>();
        for (GisProcessingInput input : inputs) {
            if (input.getSourceGroupSnapshot() == null || input.getSourceFileGroupId() == null || input.getSourceGroupRevision() == null)
                throw new IllegalStateException("已管理处理输入缺少文件组快照，请重新提交");
            GroupSnapshot snapshot = objectMapper.readValue(input.getSourceGroupSnapshot(), GroupSnapshot.class);
            GisFileGroup group = snapshot.group();
            if (!input.getSourceFileGroupId().equals(group.getId()) || !input.getSourceGroupRevision().equals(group.getInputRevision())
                    || !Objects.equals(input.getSourceFileMetaId(), group.getEntryFileId())
                    || !Objects.equals(input.getSourceDataSetId(), group.getDataSetId())
                    || snapshots.putIfAbsent(group.getId(), snapshot) != null)
                throw new IllegalArgumentException("处理输入文件组身份不一致或重复");
        }
        for (Long id : snapshots.values().stream().map(snapshot -> snapshot.group().getDataSetId()).distinct().sorted().toList()) {
            var dataSet = dataSetMapper.selectActiveByIdForUpdate(id);
            if (dataSet == null || !command.processingType().categoryId().equals(dataSet.getCategoryId()))
                throw new IllegalStateException("处理输入所属数据集已删除或分类已变化: " + id);
        }
        for (Long id : snapshots.keySet().stream().sorted().toList()) {
            GisFileGroup current = fileGroupMapper.lock(id);
            GisFileGroup snapshot = snapshots.get(id).group();
            if (current == null || !"COMPLETE".equals(current.getAssemblyStatus()) || !"COMPLETED".equals(current.getParseStatus())
                    || !Objects.equals(current.getDataSetId(), snapshot.getDataSetId())
                    || !Objects.equals(current.getInputRevision(), snapshot.getInputRevision())
                    || !Objects.equals(current.getEntryFileId(), snapshot.getEntryFileId())
                    || !Objects.equals(current.getFormat(), snapshot.getFormat())
                    || !Objects.equals(current.getReadOptionsJson(), snapshot.getReadOptionsJson()))
                throw new IllegalStateException("文件组在任务提交期间发生变化，请重新提交: " + id);
            var expectedIds = snapshots.get(id).members().stream().map(GisFileMeta::getId).collect(Collectors.toSet());
            var currentIds = fileGroupMapper.members(id).stream().map(GisFileMeta::getId).collect(Collectors.toSet());
            if (!expectedIds.equals(currentIds)) throw new IllegalStateException("文件组成员已变化，请重新提交: " + id);
        }
        Map<Long, GisFileMeta> members = snapshots.values().stream().flatMap(snapshot -> snapshot.members().stream())
                .collect(Collectors.toMap(GisFileMeta::getId, member -> member));
        for (Long id : members.keySet().stream().sorted().toList()) {
            GisFileMeta current = fileMetaMapper.selectActiveByIdForUpdate(id);
            GisFileMeta snapshot = members.get(id);
            if (current == null || !"READY".equals(current.getFileStatus())
                    || !Objects.equals(current.getDataSetId(), snapshot.getDataSetId())
                    || !Objects.equals(current.getFileGroupId(), snapshot.getFileGroupId())
                    || !Objects.equals(current.getMemberPath(), snapshot.getMemberPath())
                    || !Objects.equals(current.getOriginalName(), snapshot.getOriginalName())
                    || !Objects.equals(current.getSourceType(), snapshot.getSourceType())
                    || !Objects.equals(current.getWorkspaceCode(), snapshot.getWorkspaceCode())
                    || !Objects.equals(current.getRelativePath(), snapshot.getRelativePath())
                    || !Objects.equals(current.getSourceModifiedAt(), snapshot.getSourceModifiedAt())
                    || !Objects.equals(current.getSizeBytes(), snapshot.getSizeBytes())
                    || !Objects.equals(current.getStorageType(), snapshot.getStorageType())
                    || !Objects.equals(current.getStorageKey(), snapshot.getStorageKey())
                    || !Objects.equals(current.getSha256(), snapshot.getSha256()))
                throw new IllegalStateException("处理输入成员已删除或发生变化，请重新提交: " + id);
            if (id.equals(snapshots.get(snapshot.getFileGroupId()).group().getEntryFileId())
                    && !"COMPLETED".equals(current.getMetadataStatus()))
                throw new IllegalStateException("处理输入组入口解析结果已失效，请重新提交: " + id);
        }
    }

    private GisProcessingTask createQueuedTask(
            String taskNo, String taskName, long totalCount, LocalDateTime now) {
        GisProcessingTask task = new GisProcessingTask();
        task.setId(IdWorker.getId());
        task.setTaskNo(taskNo);
        task.setTaskName(taskName);
        task.setPriority(0);
        task.setTotalCount(totalCount);
        task.setCompletedCount(0L);
        task.setFailedCount(0L);
        task.setTaskStatus(TaskStatus.QUEUED);
        task.setCurrentStage(TaskStatus.QUEUED.name());
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        return task;
    }

    private String writeParameters(GisSubmitProcessingCommand command) {
        try {
            return objectMapper.writeValueAsString(command.parameters());
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("处理参数无法序列化", ex);
        }
    }

    private String fingerprint(GisSubmitProcessingCommand command,
            List<GisProcessingInput> inputs, String parametersJson) {
        StringBuilder value = new StringBuilder(command.processingType().name())
                .append('|').append(command.sourceType().name()).append('|')
                .append(parametersJson);
        for (GisProcessingInput input : inputs) {
            value.append('|').append(input.getSourceFileMetaId())
                    .append('|').append(input.getSourceFileGroupId())
                    .append('|').append(input.getSourceGroupRevision())
                    .append('|').append(input.getSourceGroupSnapshot())
                    .append('|').append(input.getSourceDataSetId())
                    .append('|').append(input.getSourceStorageType())
                    .append('|').append(input.getSourceStorageKey())
                    .append('|').append(input.getSourceLocatorType())
                    .append('|').append(input.getSourceModifiedAt())
                    .append('|').append(input.getSizeBytes())
                    .append('|').append(input.getWorkspaceCode())
                    .append('|').append(input.getRelativePath())
                    .append('|').append(input.getStorageKey())
                    .append('|').append(input.getSha256());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
    }

    public record CreatedProcessingTask(Long taskId, Long tileSetId) { }
}
