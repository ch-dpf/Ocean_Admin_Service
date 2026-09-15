package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.vo.GisProcessingTaskVO;
import org.ocean.admin.kernel.task.TaskProgressService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GisFolderProcessingServiceTest {

    @Test
    void submitsArbitraryServerFolderAsOneTerrainTask() throws Exception {
        Path folder = Files.createTempDirectory("gis-server-folder");
        Files.writeString(folder.resolve("dem.tif"), "test");
        GisProcessingStorageService storageService = mock(GisProcessingStorageService.class);
        GisFolderProcessingTaskService transactionService = mock(GisFolderProcessingTaskService.class);
        GisFolderProcessingWorker worker = mock(GisFolderProcessingWorker.class);
        GisFileProcessingEngineRegistry engineRegistry = mock(GisFileProcessingEngineRegistry.class);
        TaskProgressService progressService = mock(TaskProgressService.class);
        GisProcessingWorkspace workspace = new GisProcessingWorkspace(
                "terrain/folders/TASK", Path.of("tiles"), Path.of("temp"), Path.of("engine.log"));
        when(storageService.folderWorkspace(eq(GisProcessingType.TERRAIN), any()))
                .thenReturn(workspace);
        when(transactionService.create(any(), eq(folder.toAbsolutePath().normalize()), eq(workspace)))
                .thenAnswer(invocation -> new GisFolderProcessingExecution(
                        10L, invocation.getArgument(0), folder, workspace));
        GisFolderProcessingService service = new GisFolderProcessingService(
                storageService,
                engineRegistry,
                transactionService,
                worker,
                mock(GisTaskService.class),
                progressService);

        GisProcessingTaskVO result = service.submit(folder.toString());

        assertEquals(10L, result.getTaskId());
        assertEquals("TERRAIN", result.getProcessingType());
        assertEquals("QUEUED", result.getStatus());
        assertEquals("terrain/folders/TASK", result.getOutputKey());
        verify(worker).process(any(GisFolderProcessingExecution.class));
        verify(progressService).registerTask(
                eq(result.getTaskNo()), any(), eq(1), eq("GIS_TERRAIN"));
    }

    @Test
    void rejectsMissingOrEmptyFolder() throws Exception {
        GisFolderProcessingService service = new GisFolderProcessingService(
                mock(GisProcessingStorageService.class),
                mock(GisFileProcessingEngineRegistry.class),
                mock(GisFolderProcessingTaskService.class),
                mock(GisFolderProcessingWorker.class),
                mock(GisTaskService.class),
                mock(TaskProgressService.class));
        Path emptyFolder = Files.createTempDirectory("gis-empty-folder");

        assertThrows(IllegalArgumentException.class, () -> service.submit("missing-folder"));
        assertThrows(IllegalArgumentException.class, () -> service.submit(emptyFolder.toString()));
    }
}
