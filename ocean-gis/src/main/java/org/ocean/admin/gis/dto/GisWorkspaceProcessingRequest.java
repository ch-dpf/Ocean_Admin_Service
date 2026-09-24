package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.ocean.admin.gis.processing.GisProcessingType;

/** 受控服务器工作空间处理请求。 */
public record GisWorkspaceProcessingRequest(
        @NotNull GisProcessingType processingType,
        @NotBlank @Schema(description = "任务名称") String taskName,
        @NotBlank @Schema(description = "配置的工作空间编码") String workspaceCode,
        @NotBlank @Schema(description = "工作空间根目录下的相对文件或目录路径") String relativePath,
        @Valid @NotNull GisProcessingParameters parameters) {
}
