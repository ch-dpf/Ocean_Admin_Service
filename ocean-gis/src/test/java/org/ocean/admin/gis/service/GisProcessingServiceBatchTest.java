package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.TempFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisBatchProcessingTaskVO;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GisProcessingServiceBatchTest {
    @Test
    void stagesFilesAndDispatchesBatchWorker() {
        FileUploadUtil uploadUtil = mock(FileUploadUtil.class);
        GisProcessingStorageService storage = mock(GisProcessingStorageService.class);
        GisFileProcessingEngineRegistry registry = mock(GisFileProcessingEngineRegistry.class);
        GisFileProcessingEngine engine = mock(GisFileProcessingEngine.class);
        GisProcessingTaskService transactionService = mock(GisProcessingTaskService.class);
        GisProcessingWorker worker = mock(GisProcessingWorker.class);
        GisTaskService taskService = mock(GisTaskService.class);
        TaskProgressService progress = mock(TaskProgressService.class);
        MockMultipartFile file = new MockMultipartFile("files", "input.tif", "image/tiff", new byte[]{1});
        TempFile staged = TempFile.builder()
                .originalName("input.tif").stagingKey(".staging/TASK/input.tif")
                .storageName("input.tif").extension("tif").build();
        GisStoredFile stored = GisStoredFile.builder()
                .storageKey("processing/TASK/input.tif").build();
        GisCreateProcessingTaskRequest request = new GisCreateProcessingTaskRequest(
                GisProcessingType.TERRAIN, "地形任务",
                new GisProcessingParameters("EPSG:4326", "GEODETIC", "QUANTIZED_MESH"));
        GisProcessingWorkspace workspace = new GisProcessingWorkspace("terrain/batches/TASK",
                Path.of("tiles"), Path.of("temp"), Path.of("engine.log"));
        when(registry.require(GisProcessingType.TERRAIN)).thenReturn(engine);
        when(storage.batchWorkspace(eq(GisProcessingType.TERRAIN), any())).thenReturn(workspace);
        when(uploadUtil.stage(any(), eq(file))).thenReturn(staged);
        when(uploadUtil.resolveStoredPath(staged.getStagingKey())).thenReturn(Path.of("input.tif"));
        when(uploadUtil.commitForProcessing(any(), eq(staged))).thenReturn(stored);
        when(transactionService.createBatch(any(), eq(request), eq(workspace), anyList(), anyList()))
                .thenAnswer(invocation -> new GisBatchProcessingExecution(7L,
                        invocation.getArgument(0), GisProcessingType.TERRAIN, workspace, List.of()));
        GisProcessingService service = new GisProcessingService(mock(GisFileMetaService.class),
                mock(GisDataSetMapper.class), uploadUtil, storage, registry, transactionService,
                worker, new GisTaskLifecycleService(taskService, progress), taskService,
                mock(GisProcessingTaskFileMapper.class));

        GisBatchProcessingTaskVO result = service.submitBatch(request, List.of(file));

        assertEquals(7L, result.getTaskId());
        assertEquals(1, result.getTotalCount());
        verify(worker).process(any(GisBatchProcessingExecution.class));
        verify(progress).registerTask(eq(result.getTaskNo()), eq("地形任务"), eq(1), eq("GIS_TERRAIN"));
    }
}
