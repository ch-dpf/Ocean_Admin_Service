package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.processing.input.GisProcessingInputResolverRegistry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** 仅接收任务 ID，并从持久化快照恢复处理上下文。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessingWorker {
    private final GisProcessingInputMapper inputMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisProcessingInputResolverRegistry inputResolverRegistry;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisProcessingStorageService storageService;
    private final GisProcessingTaskLifecycleService taskLifecycle;
    private final GisProcessingTaskRecordService taskService;
    private final ObjectMapper objectMapper;

    @Async("gisTaskExecutor")
    public void process(Long taskId) {
        GisProcessingTask task = taskService.getRequired(taskId);
        GisTileSet tileSet = tileSetMapper.selectByTaskId(taskId);
        String taskNo = task.getTaskNo();
        if (tileSet == null) {
            throw new IllegalStateException("处理任务对应的瓦片集不存在: " + taskId);
        }
        List<GisProcessingInput> inputs = inputMapper.selectByTaskId(taskId);
        try {
            GisProcessingType processingType =
                    GisProcessingType.valueOf(task.getProcessingType());
            GisInputSourceType sourceType = GisInputSourceType.valueOf(task.getSourceType());
            GisProcessingParameters parameters = objectMapper.readValue(
                    task.getParametersJson(), GisProcessingParameters.class);
            List<Path> paths = inputResolverRegistry.require(sourceType).resolveRuntime(inputs);
            GisProcessingWorkspace workspace = storageService.workspaceFromOutputKey(
                    tileSet.getOutputKey());
            GisFileProcessingEngine engine = engineRegistry.require(processingType);

            taskLifecycle.start(taskId, taskNo, 0, "开始执行切片引擎");
            inputMapper.updateTaskStatus(taskId, "RUNNING", null);
            if (inputs.size() == 1 && "DIRECTORY".equals(inputs.get(0).getInputKind())) {
                engine.processFolder(paths.get(0), workspace,
                        progress -> taskLifecycle.reportProgress(taskNo, progress));
            } else {
                engine.process(paths, workspace, parameters,
                        progress -> taskLifecycle.reportProgress(taskNo, progress));
            }
            refreshTileMetadata(tileSet, workspace);
            tileSet.setTileSetStatus("READY");
            tileSet.setUpdateTime(LocalDateTime.now());
            tileSetMapper.updateById(tileSet);
            inputMapper.updateTaskStatus(taskId, "CONSUMED", null);
            for (int i = 0; i < inputs.size(); i++) {
                taskLifecycle.recordResult(taskId, taskNo, true);
            }
            taskLifecycle.finish(taskId, taskNo,
                    finished -> "处理结束，已生成一个静态瓦片集");
        } catch (Exception ex) {
            String error = abbreviate(ex.getMessage());
            inputMapper.updateTaskStatus(taskId, "FAILED", error);
            tileSet.setTileSetStatus("FAILED");
            tileSet.setErrorMessage(error);
            tileSet.setUpdateTime(LocalDateTime.now());
            tileSetMapper.updateById(tileSet);
            taskLifecycle.fail(taskId, taskNo, "切片处理失败: ", ex);
            log.error("GIS切片任务失败: taskId={}", taskId, ex);
        }
    }

    private void refreshTileMetadata(GisTileSet tileSet, GisProcessingWorkspace workspace) {
        if (!"IMAGERY".equals(tileSet.getTileType())) {
            return;
        }
        Path manifest = workspace.outputPath().resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        try {
            JsonNode json = objectMapper.readTree(manifest.toFile());
            tileSet.setTargetCrs(json.path("targetCrs").asText(tileSet.getTargetCrs()));
            tileSet.setTileProfile(json.path("tileProfile").asText(tileSet.getTileProfile()));
            tileSet.setOutputFormat(json.path("format").asText(tileSet.getOutputFormat()));
            tileSet.setMinZoom(json.path("minZoom").isNumber()
                    ? json.path("minZoom").asInt() : tileSet.getMinZoom());
            tileSet.setMaxZoom(json.path("maxZoom").isNumber()
                    ? json.path("maxZoom").asInt() : tileSet.getMaxZoom());
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取切片产物元数据", ex);
        }
    }

    private String abbreviate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
