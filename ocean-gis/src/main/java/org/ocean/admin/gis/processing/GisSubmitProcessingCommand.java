package org.ocean.admin.gis.processing;

import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 三种处理入口汇聚后的应用层命令。 */
public record GisSubmitProcessingCommand(
        GisInputSourceType sourceType,
        GisProcessingType processingType,
        String taskName,
        GisProcessingParameters parameters,
        List<MultipartFile> uploadFiles,
        String workspaceCode,
        String relativePath,
        List<Long> fileMetaIds) {
}
