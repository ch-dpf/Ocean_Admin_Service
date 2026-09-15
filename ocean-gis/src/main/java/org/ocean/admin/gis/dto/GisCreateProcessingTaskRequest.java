package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.ocean.admin.gis.processing.GisProcessingType;

/** 多文件切片任务请求，文件由 multipart 的 files 部分提供。 */
public record GisCreateProcessingTaskRequest(
        @NotNull @Schema(description = "处理类型", allowableValues = {"IMAGERY", "TERRAIN", "VECTOR"})
        GisProcessingType processingType,
        @NotBlank @Schema(description = "任务名称") String taskName,
        @Valid @NotNull GisProcessingParameters parameters) {
}
