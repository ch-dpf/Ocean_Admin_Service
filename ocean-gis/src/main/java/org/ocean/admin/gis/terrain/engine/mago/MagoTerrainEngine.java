package org.ocean.admin.gis.terrain.engine.mago;

import org.ocean.admin.gis.terrain.engine.TerrainEngine;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationResult;
import org.ocean.admin.gis.terrain.engine.TerrainProgressListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 通过独立 Java 子进程调用 mago-3d-terrainer。 */
public class MagoTerrainEngine implements TerrainEngine {

    private final MagoTerrainProperties properties;
    private final MagoCommandBuilder commandBuilder;
    private final MagoProcessRunner processRunner;

    public MagoTerrainEngine(
            MagoTerrainProperties properties,
            MagoCommandBuilder commandBuilder,
            MagoProcessRunner processRunner) {
        this.properties = properties;
        this.commandBuilder = commandBuilder;
        this.processRunner = processRunner;
    }

    @Override
    public TerrainGenerationResult generate(
            TerrainGenerationRequest request,
            TerrainProgressListener progressListener) {
        validateRequest(request);
        prepareDirectories(request);
        List<String> command = commandBuilder.build(properties, request);
        MagoProcessRunner.ProcessResult generationResult = processRunner.run(
                command,
                properties.getProcessTimeout(),
                progressListener);
        Duration elapsed = generationResult.elapsed();
        if (request.options().generateLayerJson()) {
            double[] sourceBounds = MagoLayerJsonMetadata.readValidBounds(request.outputPath());
            MagoProcessRunner.ProcessResult layerJsonResult = processRunner.run(
                    commandBuilder.buildLayerJson(properties, request),
                    properties.getProcessTimeout(),
                    progressListener);
            MagoLayerJsonMetadata.restoreBounds(request.outputPath(), sourceBounds);
            elapsed = elapsed.plus(layerJsonResult.elapsed());
        }
        return new TerrainGenerationResult(
                request.outputPath().toAbsolutePath().normalize(),
                elapsed,
                generationResult.exitCode());
    }

    private void validateRequest(TerrainGenerationRequest request) {
        if (!properties.isEnabled()) {
            throw new MagoTerrainException("mago 地形引擎未启用");
        }
        Path jarPath = properties.getJarPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(jarPath)) {
            throw new MagoTerrainException("mago JAR 不存在: " + jarPath);
        }
        for (Path inputPath : request.inputPaths()) {
            Path input = inputPath.toAbsolutePath().normalize();
            if (!Files.isRegularFile(input) && !Files.isDirectory(input)) {
                throw new MagoTerrainException("地形输入不存在: " + input);
            }
        }
    }

    private void prepareDirectories(TerrainGenerationRequest request) {
        try {
            Files.createDirectories(request.outputPath().toAbsolutePath().normalize());
            if (request.tempPath() != null) {
                Files.createDirectories(request.tempPath().toAbsolutePath().normalize());
            }
            if (request.logPath() != null && request.logPath().getParent() != null) {
                Files.createDirectories(request.logPath().toAbsolutePath().normalize().getParent());
            }
        } catch (IOException ex) {
            throw new MagoTerrainException("无法创建 mago 工作目录", ex);
        }
    }
}
