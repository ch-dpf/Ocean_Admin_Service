package org.ocean.admin.platform.workbench.dto;

import lombok.Data;

/**
 * 任务进度消息
 *
 * @author DeepOcean
 * @since 2026-09-11
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
    private String taskType;        // 任务类型（如 TIF）
    private String fileType;        // 文件类型（LF/HF）
    private Long fileId;            // 关联文件ID
    private String stage;           // 当前阶段
    private String message;         // 阶段说明
    private boolean done;           // 是否完成
    private long timestamp;         // 时间戳
}
