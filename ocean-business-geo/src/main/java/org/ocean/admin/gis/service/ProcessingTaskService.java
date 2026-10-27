package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
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

/** 在单一事务中创建任务、处理作业、输入快照和唯一瓦片集。 */
@Service
@RequiredArgsConstructor
public class ProcessingTaskService {
    private final ProcessingTaskRecordService taskService;
    private final GisProcessingInputMapper inputMapper;
    private final GisFileMetaMapper fileMetaMapper;
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

    /** 与物理删除共用文件行锁，关闭“校验完成但任务引用尚未落库”的竞态窗口。 */
    private void lockManagedInputs(
            GisSubmitProcessingCommand command, List<GisProcessingInput> inputs) {
        if (command.sourceType() != GisInputSourceType.MANAGED_FILE) {
            return;
        }
        for (GisProcessingInput input : inputs.stream()
                .sorted(java.util.Comparator.comparing(GisProcessingInput::getSourceFileMetaId))
                .toList()) {
            GisFileMeta current = fileMetaMapper.selectActiveByIdForUpdate(
                    input.getSourceFileMetaId());
            if (current == null) {
                throw new IllegalStateException(
                        "已管理文件已被删除，处理任务未创建: " + input.getSourceFileMetaId());
            }
            if (!Objects.equals(current.getDataSetId(), input.getSourceDataSetId())
                    || !"READY".equals(current.getFileStatus()) || !"COMPLETED".equals(current.getMetadataStatus())
                    || !Objects.equals(current.getSourceType(), input.getSourceLocatorType())
                    || !Objects.equals(current.getWorkspaceCode(), input.getWorkspaceCode())
                    || !Objects.equals(current.getRelativePath(), input.getRelativePath())
                    || !Objects.equals(current.getSourceModifiedAt(), input.getSourceModifiedAt())
                    || !Objects.equals(current.getSizeBytes(), input.getSizeBytes())
                    || !Objects.equals(current.getStorageType(), input.getSourceStorageType())
                    || !Objects.equals(current.getStorageKey(), input.getSourceStorageKey())
                    || !Objects.equals(current.getSha256(), input.getSha256())) {
                throw new IllegalStateException(
                        "已管理文件在任务提交期间发生变化，请重新提交: "
                                + input.getSourceFileMetaId());
            }
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
