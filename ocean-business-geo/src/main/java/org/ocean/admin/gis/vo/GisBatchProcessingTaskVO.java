package org.ocean.admin.gis.vo;

import org.ocean.admin.gis.progress.ProgressSnapshot;

import lombok.Builder;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingType;

/** 多文件处理任务提交结果。 */
@Data
@Builder
public class GisBatchProcessingTaskVO {
    private ProgressSnapshot.Reference progress;
    @Schema(description = "gis_processing_task数据库主键，用于处理任务详情查询；不是SSE进度标识")
    private Long taskId;
    private Long tileSetId;
    @Schema(description = "处理任务业务编号，同时作为SSE ProgressSnapshot.progressId")
    private String taskNo;
    private GisInputSourceType sourceType;
    private String sourceTypeName;
    private GisProcessingType processingType;
    private String outputKey;
    private Long totalCount;
    private TaskStatus taskStatus;
}
