package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.terrain.engine.TerrainEngine;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** 将通用单文件任务适配为 mago 地形切片请求。 */
public class TerrainFileProcessingEngine implements GisFileProcessingEngine {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("tif", "tiff");
    private final TerrainEngine terrainEngine;

    public TerrainFileProcessingEngine(TerrainEngine terrainEngine) {
        this.terrainEngine = terrainEngine;
    }

    @Override
    public GisProcessingType type() {
        return GisProcessingType.TERRAIN;
    }

    @Override
    public void validate(GisFileMeta fileMeta, Path inputPath) {
        String extension = fileMeta.getExtension() == null
                ? ""
                : fileMeta.getExtension().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("mago 地形切片仅支持 tif/tiff 单文件，当前文件: "
                    + fileMeta.getOriginalName());
        }
    }

    @Override
    public void process(GisProcessingExecution execution, Consumer<String> outputListener) {
        terrainEngine.generate(
                new TerrainGenerationRequest(
                        java.util.List.of(execution.inputPath()),
                        execution.workspace().outputPath(),
                        execution.workspace().tempPath(),
                        execution.workspace().logPath(),
                        TerrainOptions.defaults()),
                outputListener::accept);
    }
}
