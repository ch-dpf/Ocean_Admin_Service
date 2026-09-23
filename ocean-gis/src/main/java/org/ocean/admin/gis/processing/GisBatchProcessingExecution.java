package org.ocean.admin.gis.processing;

import org.ocean.admin.gis.entity.GisProcessingTaskFile;
import org.ocean.admin.gis.dto.GisProcessingParameters;

import java.util.List;

/** 已落库并可安全交给异步线程的多文件处理任务。 */
public record GisBatchProcessingExecution(
        Long taskId,
        String taskNo,
        GisProcessingType processingType,
        GisProcessingWorkspace workspace,
        List<GisProcessingTaskFile> files,
        GisProcessingParameters parameters) {
}
