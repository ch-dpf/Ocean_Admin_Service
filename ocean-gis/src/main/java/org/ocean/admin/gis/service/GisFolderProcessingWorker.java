package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 服务器目录地形切片后台执行器。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GisFolderProcessingWorker {

    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisTaskService gisTaskService;
    private final TaskProgressService taskProgressService;

    @Async("gisTaskExecutor")
    public void process(GisFolderProcessingExecution execution) {
        try {
            gisTaskService.markRunning(execution.taskId());
            taskProgressService.updateProgress(
                    execution.taskNo(), 5, "processing", "开始执行文件夹切片");
            GisFileProcessingEngine engine = engineRegistry.require(GisProcessingType.TERRAIN);
            engine.processFolder(
                    execution.inputFolder(),
                    execution.workspace(),
                    line -> log.debug("GIS文件夹切片引擎输出: taskNo={}, {}",
                            execution.taskNo(), line));
            gisTaskService.incrementCompleted(execution.taskId());
            taskProgressService.updateProgress(execution.taskNo(), true);
            gisTaskService.finish(execution.taskId());
            taskProgressService.finalizeTaskResult(execution.taskNo(), "文件夹切片处理完成");
        } catch (Exception ex) {
            gisTaskService.markFailed(execution.taskId(), ex.getMessage());
            taskProgressService.finalizeTaskFailure(
                    execution.taskNo(), "文件夹切片处理失败: " + ex.getMessage());
            log.error("GIS文件夹切片失败: taskNo={}, folder={}",
                    execution.taskNo(), execution.inputFolder(), ex);
        }
    }
}
