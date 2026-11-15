/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.command;

import com.gaia3d.terrain.tile.generation.*;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.TransformException;
import java.io.IOException;

/** 编排公共预处理和格式生成，不持有进程级配置。 */
public final class TerrainerPipeline {
    private final TerrainTilingContext context;
    public TerrainerPipeline(TerrainTilingContext context) { this.context = context; }

    public void execute() throws IOException, FactoryException, TransformException {
        context.phase("analyzing", "正在分析地形数据与瓦片范围");
        new AvailableTileAnalyzer(context).calculateAvailableTilesForEachDepth();
        context.phase("preprocessing", "正在标准化和重采样地形数据");
        TerrainRasterPreprocessor preprocessor = new TerrainRasterPreprocessor(context);
        preprocessor.standardizeInputRasters();
        preprocessor.prepareDepthRasters(context.getInputPath(), null);
        context.phase("generating", "正在生成地形瓦片");
        try (TerrainTileGenerator generator = new TerrainTilesetGenerator(context)) {
            generator.generate();
        }
        context.phase("finalizing", "地形瓦片与元数据已生成");
    }
}
