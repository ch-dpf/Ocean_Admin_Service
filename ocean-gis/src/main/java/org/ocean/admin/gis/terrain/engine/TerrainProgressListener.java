package org.ocean.admin.gis.terrain.engine;

import org.ocean.admin.gis.dto.TaskStage;

/** 接收地形引擎的结构化工作量进度。 */
@FunctionalInterface
public interface TerrainProgressListener {

    TerrainProgressListener NO_OP = progress -> { };

    void onProgress(TaskStage stage);
}
