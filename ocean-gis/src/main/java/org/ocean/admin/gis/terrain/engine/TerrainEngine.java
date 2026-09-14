package org.ocean.admin.gis.terrain.engine;

/** 地形切片引擎。 */
public interface TerrainEngine {

    /**
     * 根据不可变请求生成 Cesium 地形瓦片。
     *
     * @param request 生成请求
     * @param progressListener 引擎日志监听器
     * @return 生成结果
     */
    TerrainGenerationResult generate(
            TerrainGenerationRequest request,
            TerrainProgressListener progressListener);
}
