package org.ocean.admin.gis.terrain.engine.mago;

import org.ocean.admin.gis.terrain.engine.TerrainEngine;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationResult;
import org.ocean.admin.gis.terrain.engine.TerrainProgressListener;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.terrain.TerrainTilingService;
import java.io.IOException;

/** 直接调用本库地形模块，参数、异常和进度在此映射到 GIS 契约。 */
public class MagoTerrainEngine implements TerrainEngine {
    private final MagoTerrainProperties properties;
    private final TerrainTilingService service;

    public MagoTerrainEngine(MagoTerrainProperties properties, TerrainTilingService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override public TerrainGenerationResult generate(TerrainGenerationRequest request,
            TerrainProgressListener progressListener) {
        if (!properties.isEnabled()) throw new MagoTerrainException("地形引擎未启用");
        var options = request.options();
        var input = new TerrainTilingService.Request(request.inputPaths(), request.outputPath(),
                request.tempPath(), request.logPath(), new TerrainTilingService.Options(
                options.minDepth(), options.maxDepth(), options.geoid(), options.noDataValue(),
                options.interpolationType().name(), options.rasterMaxSize(), options.mosaicSize(),
                options.calculateNormals(), options.continuePrevious(), options.leaveTemp(), options.generateLayerJson()));
        TerrainProgressListener listener = progressListener == null ? TerrainProgressListener.NO_OP : progressListener;
        var progress = ProgressStage.throttled(listener::onProgress);
        try {
            var result = service.generate(input, event -> progress.accept(event.completed() == null
                    ? ProgressStage.indeterminate(event.phase(), event.message())
                    : new ProgressStage(event.phase(), event.message(), event.completed(), event.total(), ProgressUnit.TILE)));
            return new TerrainGenerationResult(result.outputPath(), result.elapsed());
        } catch (IOException | RuntimeException ex) {
            throw new MagoTerrainException("地形切片失败: " + ex.getMessage(), ex);
        }
    }
}
