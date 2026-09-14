package org.ocean.admin.gis.processing;

import java.nio.file.Path;

/** 交给具体切片引擎的不可变执行参数。 */
public record GisProcessingExecution(
        Long taskId,
        String taskNo,
        Long fileMetaId,
        GisProcessingType processingType,
        Path inputPath,
        GisProcessingWorkspace workspace) {
}
