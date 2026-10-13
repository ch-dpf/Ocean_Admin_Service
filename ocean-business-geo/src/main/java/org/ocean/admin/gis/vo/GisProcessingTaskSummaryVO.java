package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;

/** GIS 处理任务分页摘要。 */
@Data
public class GisProcessingTaskSummaryVO {
    private Long id;
    private String taskNo;
    private String taskName;

    @Schema(description = "处理类型：TERRAIN、IMAGERY、VECTOR")
    private GisProcessingType processingType;

    @Schema(description = "输入来源：UPLOAD、WORKSPACE、MANAGED_FILE")
    private GisInputSourceType sourceType;

    @Schema(description = "输入来源中文名称：文件上传、工作空间、已管理文件")
    private String sourceTypeName;

    private Integer priority;
    private Long totalCount;
    private Long completedCount;
    private Long failedCount;

    @Schema(description = "任务状态：QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED")
    private TaskStatus taskStatus;

    private String currentStage;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
