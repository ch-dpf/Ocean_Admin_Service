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

    @Schema(description = "数据接入方式：文件上传、工作空间、已管理文件")
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
