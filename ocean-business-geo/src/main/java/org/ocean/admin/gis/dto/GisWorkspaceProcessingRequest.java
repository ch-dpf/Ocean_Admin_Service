package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.ocean.admin.gis.processing.GisProcessingType;
import tools.jackson.databind.JsonNode;

/** 受控服务器工作空间处理请求。 */
public record GisWorkspaceProcessingRequest(
        @NotNull GisProcessingType processingType,
        @NotBlank @Schema(description = "任务名称") String taskName,
        @Schema(description = "配置的工作空间编码，未传或空白时使用 default", defaultValue = "default")
        String workspaceCode,
        @Schema(description = "工作空间根目录下的相对文件或目录路径，未传或空白时使用根目录",
                defaultValue = ".")
        String relativePath,
        @NotNull
        @Schema(oneOf = {TerrainProcessingParameters.class, ImageryProcessingParameters.class,
                GisProcessingParameters.VectorProcessingParameters.class})
        JsonNode parameters) {
}
