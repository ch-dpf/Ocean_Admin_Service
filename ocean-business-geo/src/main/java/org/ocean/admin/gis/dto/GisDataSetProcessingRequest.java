package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** 按数据集分类选择引擎，输入范围由服务端确定。 */
public record GisDataSetProcessingRequest(
        @NotBlank @Size(max = 200) String taskName,
        @NotNull @Schema(description = "影像、地形或矢量参数，与数据集分类对应；矢量固定输出 PNG",
                oneOf = {ImageryProcessingParameters.class, TerrainProcessingParameters.class, VectorParameters.class})
        JsonNode parameters) {

    /** 矢量切片使用已保存的样式和确定的数据修订。 */
    public record VectorParameters(
            @NotNull @Positive Long schemeId,
            @NotNull @Positive Long schemeVersion,
            @NotNull @PositiveOrZero Long dataRevision,
            @NotNull Integer minZoom,
            @NotNull Integer maxZoom,
            @NotNull @Size(min = 4, max = 4) List<@NotNull Double> bounds) { }
}
