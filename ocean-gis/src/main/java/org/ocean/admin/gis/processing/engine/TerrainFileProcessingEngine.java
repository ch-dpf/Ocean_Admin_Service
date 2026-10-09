package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.TerrainProcessingParameters;
import org.ocean.admin.gis.dto.TaskProgressModel;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.terrain.engine.TerrainEngine;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;

import java.nio.file.Path;
import java.util.List;
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
    public void validate(String originalName, String extension, Path inputPath) {
        extension = extension == null
                ? ""
                : extension.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("mago 地形切片仅支持 tif/tiff 单文件，当前文件: "
                    + originalName);
        }
    }

    @Override
    public void process(List<Path> inputPaths, GisProcessingWorkspace workspace,
            GisProcessingParameters parameters,
            Consumer<TaskProgressModel> progressListener) {
        terrainEngine.generate(
                new TerrainGenerationRequest(inputPaths, workspace.outputPath(),
                        workspace.tempPath(), workspace.logPath(), options(parameters)),
                progressListener::accept);
    }

    @Override
    public void processFolder(Path inputFolder, GisProcessingWorkspace workspace,
            GisProcessingParameters parameters,
            Consumer<TaskProgressModel> progressListener) {
        terrainEngine.generate(
                new TerrainGenerationRequest(
                        java.util.List.of(inputFolder),
                        workspace.outputPath(),
                        workspace.tempPath(),
                        workspace.logPath(),
                        options(parameters)),
                progressListener::accept);
    }

    private TerrainOptions options(GisProcessingParameters parameters) {
        if (!(parameters instanceof TerrainProcessingParameters terrainParameters)) {
            throw new IllegalArgumentException("地形切片任务参数类型不正确");
        }
        return TerrainOptions.from(terrainParameters);
    }
}
