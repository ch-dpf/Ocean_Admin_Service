package org.ocean.admin.gis.dto;

import lombok.Data;

/**
 * @author DeepOcean
 * @since 2026-09-16
 */
@Data
public class TaskProgressMessage {
    private String eventType;        // 事件类型（task_update/task_snapshot）
    private String taskId;          // 任务ID
    private String taskName;        // 任务名称
    private int totalCount;         // 总数
    private int completedCount;     // 已完成数
    private int failedCount;        // 失败数
    private String status;          // running, completed
    private int progress;           // 进度百分比 0-100
    private String taskType;        // 任务类型（如 上传、下载、地形处理、影像处理、矢量处理等）
    private String stage;           // 当前阶段
    private String message;         // 阶段说明
    private String progressMode;    // DETERMINATE / INDETERMINATE
    private Long completedUnits;    // 已完成工作单元
    private Long totalUnits;        // 总工作单元
    private long version;           // 任务内单调事件序号
    private boolean done;           // 是否完成
    private long timestamp;         // 时间戳
}
