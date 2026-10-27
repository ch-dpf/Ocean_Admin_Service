package org.ocean.admin.gis.vo;

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
    @Schema(description = "gis_processing_task数据库主键，用于处理任务详情查询；不是WebSocket进度标识")
    private Long taskId;
    private Long tileSetId;
    @Schema(description = "处理任务业务编号，同时作为WebSocket TaskInfo.taskId")
    private String taskNo;
    private GisInputSourceType sourceType;
    private String sourceTypeName;
    private GisProcessingType processingType;
    private String outputKey;
    private Long totalCount;
    private TaskStatus taskStatus;
}
