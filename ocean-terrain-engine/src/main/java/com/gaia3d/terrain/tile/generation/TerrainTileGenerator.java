/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.generation;

import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.TransformException;
import java.io.IOException;

/** 格式相关生成阶段；公共栅格预处理由任务上下文提供。 */
public interface TerrainTileGenerator extends AutoCloseable {
    void generate() throws IOException, FactoryException, TransformException;
    @Override void close();
}
