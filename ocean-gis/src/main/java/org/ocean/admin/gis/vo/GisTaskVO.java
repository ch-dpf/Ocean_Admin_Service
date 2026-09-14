package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** GIS 任务查询响应对象。 */
@Data
public class GisTaskVO {

    @Schema(description = "任务 ID")
    private Long id;

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "任务类型：1-上传、2-切片、3-发布、4-导出或下载")
    private Long taskType;

    @Schema(description = "优先级，数值越大优先级越高")
    private Integer priority;

    @Schema(description = "工作项总数")
    private Long totalCount;

    @Schema(description = "成功工作项数量")
    private Long completedCount;

    @Schema(description = "失败工作项数量")
    private Long failedCount;

    @Schema(description = "任务状态：QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED")
    private String taskStatus;

    @Schema(description = "当前执行阶段")
    private String currentStage;

    @Schema(description = "任务错误信息")
    private String errorMessage;

    @Schema(description = "关联数据集 ID")
    private Long dataSetId;

    @Schema(description = "父任务 ID")
    private Long parentTaskId;

    @Schema(description = "根任务 ID")
    private Long rootTaskId;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
