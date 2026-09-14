package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.dto.GisFileProcessRequest;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GisFileProcessingServiceTest {

    private final Path tempDir = Path.of("target", "test-work", "file-processing");

    @Test
    void submitsReadyLocalTerrainFileToTerrainEngine() throws Exception {
        GisFileMetaService metaService = mock(GisFileMetaService.class);
        GisDataSetMapper dataSetMapper = mock(GisDataSetMapper.class);
        GisFileProcessingEngineRegistry registry = mock(GisFileProcessingEngineRegistry.class);
        GisFileProcessingEngine engine = mock(GisFileProcessingEngine.class);
        GisFileProcessingTaskService transactionService = mock(GisFileProcessingTaskService.class);
        GisFileProcessingWorker worker = mock(GisFileProcessingWorker.class);
        GisTaskService taskService = mock(GisTaskService.class);
        TaskProgressService progressService = mock(TaskProgressService.class);
        Path uploadRoot = tempDir.resolve("uploads");
        Path input = uploadRoot.resolve("datasets/demo/source.tif");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "test");

        GisFileMeta meta = fileMeta();
        GisDataSet dataSet = new GisDataSet();
        dataSet.setId(10L);
        dataSet.setCategoryId(1L);
        when(metaService.getRequiredEntity(20L)).thenReturn(meta);
        when(dataSetMapper.selectById(10L)).thenReturn(dataSet);
        when(registry.require(GisProcessingType.TERRAIN)).thenReturn(engine);
        when(transactionService.create(any(), eq(meta), eq(GisProcessingType.TERRAIN),
                any(Path.class), any())).thenAnswer(invocation -> {
                    String taskNo = invocation.getArgument(0);
                    Path resolvedInput = invocation.getArgument(3);
                    GisProcessingWorkspace workspace = invocation.getArgument(4);
                    return new GisProcessingExecution(
                            30L, taskNo, 20L, GisProcessingType.TERRAIN, resolvedInput, workspace);
                });

        GisFileProcessingService service = new GisFileProcessingService(
                metaService,
                dataSetMapper,
                new FileStorageService(uploadRoot.toString()),
                new GisProcessingStorageService(tempDir.resolve("processed").toString()),
                registry,
                transactionService,
                worker,
                taskService,
                progressService);
        GisFileProcessRequest request = new GisFileProcessRequest();
        request.setProcessingType(GisProcessingType.TERRAIN);

        GisProcessingTaskVO result = service.submit(20L, request);

        assertEquals(30L, result.getTaskId());
        assertEquals("TERRAIN", result.getProcessingType());
        assertEquals("QUEUED", result.getStatus());
        verify(engine).validate(eq(meta), any(Path.class));
        verify(worker).process(any(GisProcessingExecution.class));
        verify(progressService).registerTask(
                eq(result.getTaskNo()), any(), eq(1), eq("GIS_TERRAIN"));
    }

    @Test
    void rejectsProcessingTypeThatDoesNotMatchDatasetCategory() {
        GisFileMetaService metaService = mock(GisFileMetaService.class);
        GisDataSetMapper dataSetMapper = mock(GisDataSetMapper.class);
        GisFileProcessingEngineRegistry registry = mock(GisFileProcessingEngineRegistry.class);
        GisFileMeta meta = fileMeta();
        GisDataSet dataSet = new GisDataSet();
        dataSet.setId(10L);
        dataSet.setCategoryId(0L);
        when(metaService.getRequiredEntity(20L)).thenReturn(meta);
        when(dataSetMapper.selectById(10L)).thenReturn(dataSet);
        GisFileProcessingService service = new GisFileProcessingService(
                metaService,
                dataSetMapper,
                mock(FileStorageService.class),
                mock(GisProcessingStorageService.class),
                registry,
                mock(GisFileProcessingTaskService.class),
                mock(GisFileProcessingWorker.class),
                mock(GisTaskService.class),
                mock(TaskProgressService.class));
        GisFileProcessRequest request = new GisFileProcessRequest();
        request.setProcessingType(GisProcessingType.TERRAIN);

        assertThrows(IllegalArgumentException.class, () -> service.submit(20L, request));
        verify(registry, never()).require(any());
    }

    private GisFileMeta fileMeta() {
        GisFileMeta meta = new GisFileMeta();
        meta.setId(20L);
        meta.setDataSetId(10L);
        meta.setTaskId(5L);
        meta.setOriginalName("source.tif");
        meta.setStorageKey("datasets/demo/source.tif");
        meta.setStorageType("LOCAL");
        meta.setExtension("tif");
        meta.setUploadStatus("READY");
        return meta;
    }
}
