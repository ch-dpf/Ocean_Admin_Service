package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
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
import java.util.List;

/** 在单一事务中创建任务、处理作业、输入快照和唯一瓦片集。 */
@Service
@RequiredArgsConstructor
public class GisProcessingTaskService {
    private final GisProcessingTaskRecordService taskService;
    private final GisProcessingInputMapper inputMapper;
    private final GisTileSetMapper tileSetMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public CreatedProcessingTask create(String taskNo, GisSubmitProcessingCommand command,
            GisProcessingWorkspace workspace, List<GisProcessingInput> inputs) {
        LocalDateTime now = LocalDateTime.now();
        String parametersJson = writeParameters(command);
        GisProcessingTask task = GisProcessingTaskFactory.queued(
                taskNo, command.taskName().trim(), inputs.size(), "QUEUED");
        task.setProcessingType(command.processingType().name());
        task.setSourceType(command.sourceType().name());
        task.setParametersJson(parametersJson);
        task.setParameterSchemaVersion(1);
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
        tileSet.setTileType(command.processingType().name());
        tileSet.setTileSetStatus("BUILDING");
        tileSet.setOutputKey(workspace.outputKey());
        tileSet.setTargetCrs(command.parameters().targetCrs());
        tileSet.setTileProfile(command.parameters().tileProfile());
        tileSet.setOutputFormat(command.parameters().outputFormat());
        tileSet.setMinZoom(command.parameters().minZoom());
        tileSet.setMaxZoom(command.parameters().maxZoom());
        tileSet.setManifestKey(workspace.outputKey() + "/tiles/"
                + ("TERRAIN".equals(tileSet.getTileType()) ? "layer.json" : "manifest.json"));
        tileSet.setCreateTime(now);
        tileSet.setUpdateTime(now);
        if (tileSetMapper.insert(tileSet) != 1) {
            throw new IllegalStateException("GIS瓦片集创建失败");
        }
        return new CreatedProcessingTask(task.getId(), tileSet.getId());
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
            value.append('|').append(input.getFileMetaId())
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
