package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** GIS 处理任务分页摘要。 */
@Data
public class GisProcessingTaskSummaryVO {
    private Long id;
    private String taskNo;
    private String taskName;

    @Schema(description = "处理类型：TERRAIN、IMAGERY、VECTOR")
    private String processingType;

    @Schema(description = "输入来源：UPLOAD、WORKSPACE、MANAGED_FILE")
    private String sourceType;

    private Integer priority;
    private Long totalCount;
    private Long completedCount;
    private Long failedCount;

    @Schema(description = "任务状态：QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED")
    private String taskStatus;

    private String currentStage;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
