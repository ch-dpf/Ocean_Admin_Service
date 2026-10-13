package org.ocean.admin.gis.dto;

/** 当前任务阶段及其局部工作量。 */
public record TaskStage(
        String code, // 阶段编码
        String message, // 阶段消息
        Long completed, // 已完成量
        Long total, // 总量
        TaskProgressUnit unit) { // 工作量单位

    public TaskStage {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("任务阶段不能为空");
        }
        message = message == null ? "" : message;
        boolean noWorkload = completed == null && total == null && unit == null;
        boolean completeWorkload = completed != null && total != null && unit != null;
        if (!noWorkload && !completeWorkload) {
            throw new IllegalArgumentException("任务阶段工作量必须完整提供");
        }
        if (completeWorkload
                && (total < 1 || completed < 0 || completed > total)) {
            throw new IllegalArgumentException("任务阶段工作量不合法");
        }
    }

    public static TaskStage indeterminate(String code, String message) {
        return new TaskStage(code, message, null, null, null);
    }

    public static TaskStage determinate(
            String code,
            String message,
            long completed,
            long total,
            TaskProgressUnit unit) {
        return new TaskStage(code, message, completed, total, unit);
    }

    public boolean determinate() {
        return total != null;
    }

    public Integer percent() {
        if (!determinate()) {
            return null;
        }
        return (int) Math.min(100, Math.floor((double) completed * 100 / total));
    }
}
