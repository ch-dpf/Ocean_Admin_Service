package org.ocean.admin.gis.dto;

import java.util.Objects;

/** WebSocket 任务进度事件。 */
public record TaskProgressMessage(
        String eventType,
        TaskInfo task,
        long timestamp) {

    public TaskProgressMessage {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("任务事件类型不能为空");
        }
        Objects.requireNonNull(task, "任务快照不能为空");
        if (timestamp < 0) {
            throw new IllegalArgumentException("任务事件时间戳不能为负数");
        }
    }
}
