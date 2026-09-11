package org.ocean.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 数据集VO
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Data
public class GisDataSetVO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "数据集名称")
    @NotNull(message = "数据集不能为空")
    private String dataSetName;

    /**
     * 数据类别:0-影像数据、1-地形数据、2-矢量数据
     */
    @Schema(description = "数据类别",defaultValue = "0")
    @NotNull(message = "数据类别不能为空")
    private Long categoryId;

    @Schema(description = "数据集描述")
    private String description;
}
