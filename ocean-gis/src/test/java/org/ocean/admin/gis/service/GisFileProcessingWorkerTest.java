package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.kernel.task.TaskProgressService;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GisFileProcessingWorkerTest {

    @Test
    void completesDatabaseAndRealtimeProgressTogether() {
        GisFileProcessingEngineRegistry registry = mock(GisFileProcessingEngineRegistry.class);
        GisFileProcessingEngine engine = mock(GisFileProcessingEngine.class);
        GisTaskService taskService = mock(GisTaskService.class);
        TaskProgressService progressService = mock(TaskProgressService.class);
        GisTask finished = new GisTask();
        finished.setTaskStatus("COMPLETED");
        when(registry.require(GisProcessingType.TERRAIN)).thenReturn(engine);
        when(taskService.finish(1L)).thenReturn(finished);
        GisFileProcessingWorker worker =
                new GisFileProcessingWorker(registry, taskService, progressService);

        worker.process(execution());

        verify(taskService).markRunning(1L);
        verify(taskService).incrementCompleted(1L);
        verify(progressService).updateProgress("TASK", true);
        verify(taskService).finish(1L);
        verify(progressService).finalizeTaskResult("TASK", "切片处理完成");
        verify(taskService, never()).markFailed(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksTaskFailedWhenEngineFails() {
        GisFileProcessingEngineRegistry registry = mock(GisFileProcessingEngineRegistry.class);
        GisFileProcessingEngine engine = mock(GisFileProcessingEngine.class);
        GisTaskService taskService = mock(GisTaskService.class);
        TaskProgressService progressService = mock(TaskProgressService.class);
        when(registry.require(GisProcessingType.TERRAIN)).thenReturn(engine);
        doThrow(new IllegalStateException("engine failed"))
                .when(engine).process(any(), any(Consumer.class));
        GisFileProcessingWorker worker =
                new GisFileProcessingWorker(registry, taskService, progressService);

        worker.process(execution());

        verify(taskService).markFailed(1L, "engine failed");
        verify(progressService).finalizeTaskFailure("TASK", "切片处理失败: engine failed");
        verify(taskService, never()).incrementCompleted(any());
    }

    private GisProcessingExecution execution() {
        Path root = Path.of("target", "test-work", "worker");
        return new GisProcessingExecution(
                1L,
                "TASK",
                2L,
                GisProcessingType.TERRAIN,
                root.resolve("source.tif"),
                new GisProcessingWorkspace(
                        "terrain/2/TASK",
                        root.resolve("tiles"),
                        root.resolve("temp"),
                        root.resolve("engine.log")));
    }
}
