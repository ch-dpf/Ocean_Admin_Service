package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.ocean.admin.gis.processing.GisProcessingType;

/** 已入库单文件处理请求。 */
@Data
public class GisFileProcessRequest {

    @NotNull(message = "处理类型不能为空")
    @Schema(description = "处理类型", allowableValues = {"TERRAIN", "IMAGERY", "VECTOR"},
            example = "TERRAIN")
    private GisProcessingType processingType;
}
