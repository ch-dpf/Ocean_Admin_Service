package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.entity.GisProcessingTaskFile;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.kernel.task.TaskProgressService;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GisBatchProcessingWorkerTest {
    @Test
    @SuppressWarnings("unchecked")
    void recordsPartialFailureAndContinuesWithNextFile() {
        FileStorageService storage = mock(FileStorageService.class);
        GisFileProcessingEngineRegistry registry = mock(GisFileProcessingEngineRegistry.class);
        GisFileProcessingEngine engine = mock(GisFileProcessingEngine.class);
        GisProcessingTaskFileMapper fileMapper = mock(GisProcessingTaskFileMapper.class);
        GisTaskService taskService = mock(GisTaskService.class);
        TaskProgressService progress = mock(TaskProgressService.class);
        when(registry.require(GisProcessingType.TERRAIN)).thenReturn(engine);
        when(storage.resolveStoredPath(any())).thenReturn(Path.of("input.tif"));
        when(fileMapper.updateStatus(any(), any(), any())).thenReturn(1);
        when(fileMapper.updateStatus(any(), any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(1);
        doThrow(new IllegalStateException("bad raster")).doNothing()
                .when(engine).process(any(), any(Consumer.class));
        GisTask finished = new GisTask();
        finished.setCompletedCount(1L);
        finished.setFailedCount(1L);
        finished.setTaskStatus("PARTIAL_FAILED");
        when(taskService.finish(1L)).thenReturn(finished);
        Path root = Path.of("target", "test-work", "batch-worker");
        GisProcessingWorkspace workspace = new GisProcessingWorkspace(
                "terrain/batches/TASK", root.resolve("tiles"), root.resolve("temp"),
                root.resolve("logs/engine.log"));
        GisBatchProcessingExecution execution = new GisBatchProcessingExecution(
                1L, "TASK", GisProcessingType.TERRAIN, workspace,
                List.of(file(11L, 1), file(12L, 2)));

        new GisBatchProcessingWorker(storage, registry, fileMapper,
                taskService, progress).process(execution);

        verify(storage, atLeastOnce()).resolveStoredPath(any());
        verify(engine, times(2)).process(any(), any(Consumer.class));
        verify(taskService).incrementFailed(1L);
        verify(taskService).incrementCompleted(1L);
        verify(taskService).finish(1L);
        verify(taskService, never()).markFailed(any(), any());
        verify(progress).updateProgress("TASK", false);
        verify(progress).updateProgress("TASK", true);
    }

    private GisProcessingTaskFile file(Long id, int index) {
        GisProcessingTaskFile file = new GisProcessingTaskFile();
        file.setId(id);
        file.setFileIndex(index);
        file.setOriginalName("input" + index + ".tif");
        file.setStorageKey("processing/TASK/input" + index + ".tif");
        file.setOutputKey("terrain/batches/TASK/tiles/" + String.format("%04d", index));
        return file;
    }
}
