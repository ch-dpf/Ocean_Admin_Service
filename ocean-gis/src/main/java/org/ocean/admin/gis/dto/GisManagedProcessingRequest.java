package org.ocean.admin.gis.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.util.List;

/** 使用文件元数据管理模块中的文件创建处理任务。 */
public record GisManagedProcessingRequest(
        @NotNull GisProcessingType processingType,
        @NotBlank String taskName,
        @NotEmpty List<@NotNull Long> fileMetaIds,
        @Valid @NotNull GisProcessingParameters parameters) {
}
