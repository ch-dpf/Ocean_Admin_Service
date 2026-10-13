package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

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

    @Schema(
            description = "数据类别：0-影像数据、1-地形数据、2-矢量数据",
            allowableValues = {"0", "1", "2"},
            example = "0"
    )
    @NotNull(message = "数据类别不能为空")
    private Long categoryId;

    @Schema(description = "数据集描述")
    private String description;

    @Schema(description = "数据集编码", accessMode = Schema.AccessMode.READ_ONLY)
    private String dataSetCode;

    @Schema(description = "已就绪文件数量", accessMode = Schema.AccessMode.READ_ONLY)
    private Integer fileCount;

    @Schema(description = "创建时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime createTime;

    @Schema(description = "更新时间", accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime updateTime;
}
