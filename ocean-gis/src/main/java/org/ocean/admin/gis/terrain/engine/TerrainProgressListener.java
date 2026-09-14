package org.ocean.admin.gis.terrain.engine;

/** 接收地形引擎的原始输出，后续可在 Worker 层解析为结构化进度。 */
@FunctionalInterface
public interface TerrainProgressListener {

    TerrainProgressListener NO_OP = line -> { };

    void onOutput(String line);
}
