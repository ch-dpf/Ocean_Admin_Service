package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisProcessingTaskFile;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/** 多文件任务逐文件运行切片引擎。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GisBatchProcessingWorker {
    private final FileStorageService fileStorageService;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisProcessingTaskFileMapper fileMapper;
    private final GisTaskService taskService;
    private final TaskProgressService taskProgressService;

    @Async("gisTaskExecutor")
    public void process(GisBatchProcessingExecution execution) {
        try {
            taskService.markRunning(execution.taskId());
            taskProgressService.updateProgress(execution.taskNo(), 0,
                    "processing", "开始执行多文件切片");
            GisFileProcessingEngine engine = engineRegistry.require(execution.processingType());
            for (GisProcessingTaskFile file : execution.files()) {
                boolean success = processFile(execution, file, engine);
                if (success) {
                    taskService.incrementCompleted(execution.taskId());
                } else {
                    taskService.incrementFailed(execution.taskId());
                }
                taskProgressService.updateProgress(execution.taskNo(), success);
            }
            GisTask finished = taskService.finish(execution.taskId());
            taskProgressService.finalizeTaskResult(execution.taskNo(),
                    "处理结束，成功" + finished.getCompletedCount()
                            + "个，失败" + finished.getFailedCount() + "个");
        } catch (Exception ex) {
            taskService.markFailed(execution.taskId(), ex.getMessage());
            taskProgressService.finalizeTaskFailure(execution.taskNo(),
                    "多文件处理失败: " + ex.getMessage());
            log.error("GIS多文件切片任务失败: taskNo={}", execution.taskNo(), ex);
        }
    }

    private boolean processFile(GisBatchProcessingExecution execution,
            GisProcessingTaskFile file, GisFileProcessingEngine engine) {
        try {
            updateFile(file.getId(), "RUNNING", null);
            Path input = fileStorageService.resolveStoredPath(file.getStorageKey());
            String segment = String.format("%04d", file.getFileIndex());
            GisProcessingWorkspace taskWorkspace = execution.workspace();
            GisProcessingWorkspace fileWorkspace = new GisProcessingWorkspace(
                    file.getOutputKey(),
                    taskWorkspace.outputPath().resolve(segment),
                    taskWorkspace.tempPath().resolve(segment),
                    taskWorkspace.logPath().getParent().resolve(segment + ".log"));
            engine.process(new GisProcessingExecution(execution.taskId(), execution.taskNo(),
                    null, execution.processingType(), input, fileWorkspace),
                    line -> log.debug("GIS多文件切片引擎输出: taskNo={}, file={}, {}",
                            execution.taskNo(), file.getOriginalName(), line));
            updateFile(file.getId(), "COMPLETED", null);
            return true;
        } catch (Exception ex) {
            updateFile(file.getId(), "FAILED", abbreviate(ex.getMessage()));
            log.error("GIS多文件切片失败: taskNo={}, file={}",
                    execution.taskNo(), file.getOriginalName(), ex);
            return false;
        }
    }

    private void updateFile(Long id, String status, String error) {
        int updated = fileMapper.updateStatus(id, status, error);
        if (updated != 1) {
            throw new IllegalStateException("处理文件状态更新失败: " + id);
        }
    }

    private String abbreviate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
