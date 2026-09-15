package org.ocean.admin.gis.processing;

import java.nio.file.Path;

/** 交给地形引擎的服务器目录切片执行参数。 */
public record GisFolderProcessingExecution(
        Long taskId,
        String taskNo,
        Path inputFolder,
        GisProcessingWorkspace workspace) {
}
