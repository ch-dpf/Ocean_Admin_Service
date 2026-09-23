package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import lombok.Data;
import org.ocean.admin.gis.processing.GisProcessingType;

/** 已入库单文件处理请求。 */
@Data
public class GisFileProcessRequest {

    @NotNull(message = "处理类型不能为空")
    @Schema(description = "处理类型", allowableValues = {"TERRAIN", "IMAGERY", "VECTOR"},
            example = "TERRAIN")
    private GisProcessingType processingType;

    @Valid
    @Schema(description = "可选处理参数；不传时使用对应引擎默认值")
    private GisProcessingParameters parameters;
}
