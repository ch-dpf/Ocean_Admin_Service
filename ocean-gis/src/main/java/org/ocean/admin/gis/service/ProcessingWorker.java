package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisProcessingTaskFile;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/** 三种 GIS 切片输入的异步执行入口。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessingWorker {
    private final FileUploadUtil fileUploadUtil;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisProcessingTaskFileMapper fileMapper;
    private final GisTaskLifecycleService taskLifecycle;

    @Async("gisTaskExecutor")
    public void process(GisProcessingExecution execution) {
        try {
            taskLifecycle.start(execution.taskId(), execution.taskNo(), 5, "开始执行切片引擎");
            GisFileProcessingEngine engine = engineRegistry.require(execution.processingType());
            engine.process(execution,
                    line -> log.debug("GIS切片引擎输出: taskNo={}, {}", execution.taskNo(), line));
            taskLifecycle.recordResult(execution.taskId(), execution.taskNo(), true);
            taskLifecycle.finish(execution.taskId(), execution.taskNo(), ignored -> "切片处理完成");
        } catch (Exception ex) {
            taskLifecycle.fail(execution.taskId(), execution.taskNo(), "切片处理失败: ", ex);
            log.error("GIS单文件切片失败: taskNo={}, fileMetaId={}",
                    execution.taskNo(), execution.fileMetaId(), ex);
        }
    }

    @Async("gisTaskExecutor")
    public void process(GisFolderProcessingExecution execution) {
        try {
            taskLifecycle.start(execution.taskId(), execution.taskNo(), 5, "开始执行文件夹切片");
            GisFileProcessingEngine engine = engineRegistry.require(GisProcessingType.TERRAIN);
            engine.processFolder(execution.inputFolder(), execution.workspace(),
                    line -> log.debug("GIS文件夹切片引擎输出: taskNo={}, {}",
                            execution.taskNo(), line));
            taskLifecycle.recordResult(execution.taskId(), execution.taskNo(), true);
            taskLifecycle.finish(execution.taskId(), execution.taskNo(),
                    ignored -> "文件夹切片处理完成");
        } catch (Exception ex) {
            taskLifecycle.fail(execution.taskId(), execution.taskNo(),
                    "文件夹切片处理失败: ", ex);
            log.error("GIS文件夹切片失败: taskNo={}, folder={}",
                    execution.taskNo(), execution.inputFolder(), ex);
        }
    }

    @Async("gisTaskExecutor")
    public void process(GisBatchProcessingExecution execution) {
        try {
            taskLifecycle.start(execution.taskId(), execution.taskNo(), 0, "开始执行多文件切片");
            GisFileProcessingEngine engine = engineRegistry.require(execution.processingType());
            execution.files().forEach(file -> updateFile(file.getId(), "RUNNING", null));
            java.util.List<Path> inputs = execution.files().stream()
                    .map(file -> fileUploadUtil.resolveStoredPath(file.getStorageKey()))
                    .toList();
            engine.process(inputs, execution.workspace(),
                    line -> log.debug("GIS多文件切片引擎输出: taskNo={}, {}", execution.taskNo(), line));
            for (GisProcessingTaskFile file : execution.files()) {
                updateFile(file.getId(), "COMPLETED", null);
                taskLifecycle.recordResult(execution.taskId(), execution.taskNo(), true);
            }
            taskLifecycle.finish(execution.taskId(), execution.taskNo(),
                    finished -> "处理结束，成功" + finished.getCompletedCount()
                            + "个，失败" + finished.getFailedCount() + "个");
        } catch (Exception ex) {
            execution.files().forEach(file -> updateFile(file.getId(), "FAILED", abbreviate(ex.getMessage())));
            for (int i = 0; i < execution.files().size(); i++) {
                taskLifecycle.recordResult(execution.taskId(), execution.taskNo(), false);
            }
            taskLifecycle.fail(execution.taskId(), execution.taskNo(), "多文件处理失败: ", ex);
            log.error("GIS多文件切片任务失败: taskNo={}", execution.taskNo(), ex);
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
