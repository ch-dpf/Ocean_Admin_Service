package org.ocean.admin.gis.dto;

import java.time.LocalDateTime;
import java.util.Objects;

/** 可供查询、缓存和实时推送的不可变任务快照。 */
public record TaskInfo(
        String taskId,  // 任务id
        String taskName,  // 任务名称
        String taskType,  // 任务类型
        TaskStatus status,  // 任务状态
        Integer overallPercent, // 任务整体进度
        TaskResult result,  // 任务处理结果
        TaskStage currentStage,  // 任务当前阶段
        LocalDateTime startTime,  // 任务开始时间
        LocalDateTime endTime,  // 任务结束时间
        long version) {

    public TaskInfo {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        Objects.requireNonNull(status, "任务状态不能为空");
        Objects.requireNonNull(result, "任务处理结果不能为空");
        Objects.requireNonNull(currentStage, "任务当前阶段不能为空");
        Objects.requireNonNull(startTime, "任务开始时间不能为空");
        if (overallPercent != null && (overallPercent < 0 || overallPercent > 100)) {
            throw new IllegalArgumentException("任务整体进度必须在0到100之间");
        }
        if (status.isTerminal() && endTime == null) {
            throw new IllegalArgumentException("终态任务必须包含结束时间");
        }
        if (!status.isTerminal() && endTime != null) {
            throw new IllegalArgumentException("运行中任务不能包含结束时间");
        }
        if (version < 0) {
            throw new IllegalArgumentException("任务版本号不能为负数");
        }
    }
}
