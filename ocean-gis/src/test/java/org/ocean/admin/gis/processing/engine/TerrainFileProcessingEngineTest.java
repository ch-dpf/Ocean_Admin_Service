package org.ocean.admin.gis.processing.engine;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.terrain.engine.TerrainEngine;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainProgressListener;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TerrainFileProcessingEngineTest {

    private final Path tempDir = Path.of("target", "test-work", "terrain-adapter");

    @Test
    void adaptsSingleTiffToTerrainEngineRequest() {
        TerrainEngine terrainEngine = mock(TerrainEngine.class);
        TerrainFileProcessingEngine engine = new TerrainFileProcessingEngine(terrainEngine);
        Path input = tempDir.resolve("source.tif");
        GisProcessingWorkspace workspace = new GisProcessingWorkspace(
                "terrain/1/task", tempDir.resolve("tiles"),
                tempDir.resolve("temp"), tempDir.resolve("engine.log"));
        GisProcessingExecution execution = new GisProcessingExecution(
                2L, "task", 1L, GisProcessingType.TERRAIN, input, workspace);

        engine.process(execution, line -> { });

        ArgumentCaptor<TerrainGenerationRequest> requestCaptor =
                ArgumentCaptor.forClass(TerrainGenerationRequest.class);
        verify(terrainEngine).generate(requestCaptor.capture(), any(TerrainProgressListener.class));
        TerrainGenerationRequest request = requestCaptor.getValue();
        assertEquals(java.util.List.of(input), request.inputPaths());
        assertEquals(workspace.outputPath(), request.outputPath());
        assertEquals(workspace.tempPath(), request.tempPath());
        assertEquals(workspace.logPath(), request.logPath());
    }

    @Test
    void rejectsUnsupportedTerrainExtension() {
        TerrainFileProcessingEngine engine =
                new TerrainFileProcessingEngine(mock(TerrainEngine.class));
        GisFileMeta meta = new GisFileMeta();
        meta.setOriginalName("height.png");
        meta.setExtension("png");

        assertThrows(IllegalArgumentException.class,
                () -> engine.validate(meta, tempDir.resolve("height.png")));
    }
}
