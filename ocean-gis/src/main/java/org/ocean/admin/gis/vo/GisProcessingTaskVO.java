package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/** 单文件切片任务提交结果。 */
@Data
@Builder
public class GisProcessingTaskVO {

    private Long taskId;
    private String taskNo;
    private Long fileMetaId;
    private Long dataSetId;

    @Schema(description = "处理类型：TERRAIN、IMAGERY、VECTOR")
    private String processingType;

    @Schema(description = "切片产物相对 Key")
    private String outputKey;

    private String status;
}
