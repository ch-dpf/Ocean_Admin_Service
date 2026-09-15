package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.kernel.task.TaskProgressService;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GisProcessingWorkerFolderTest {
    @Test
    @SuppressWarnings("unchecked")
    void executesFolderThroughTerrainEngineAndFinishesTask() {
        GisFileProcessingEngineRegistry registry = mock(GisFileProcessingEngineRegistry.class);
        GisFileProcessingEngine engine = mock(GisFileProcessingEngine.class);
        GisTaskService taskService = mock(GisTaskService.class);
        TaskProgressService progressService = mock(TaskProgressService.class);
        when(registry.require(GisProcessingType.TERRAIN)).thenReturn(engine);
        when(taskService.finish(1L)).thenReturn(new GisTask());
        Path folder = Path.of("target", "test-work", "folder-worker");
        GisProcessingWorkspace workspace = new GisProcessingWorkspace(
                "terrain/folders/TASK", folder.resolve("tiles"), folder.resolve("temp"),
                folder.resolve("engine.log"));
        GisFolderProcessingExecution execution = new GisFolderProcessingExecution(
                1L, "TASK", folder, workspace);
        GisProcessingWorker worker = new GisProcessingWorker(mock(FileUploadUtil.class), registry,
                mock(GisProcessingTaskFileMapper.class),
                new GisTaskLifecycleService(taskService, progressService));

        worker.process(execution);

        verify(engine).processFolder(any(Path.class), any(GisProcessingWorkspace.class),
                any(Consumer.class));
        verify(taskService).incrementCompleted(1L);
        verify(progressService).finalizeTaskResult("TASK", "文件夹切片处理完成");
    }
}
