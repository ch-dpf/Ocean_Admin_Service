package org.ocean.admin.gis.progress;

/** 当前任务阶段及其局部工作量。 */
public record ProgressStage(
        String code, // 阶段编码
        String message, // 阶段消息
        Long completed, // 已完成量
        Long total, // 总量
        ProgressUnit unit) { // 工作量单位

    public ProgressStage {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("任务阶段不能为空");
        }
        message = message == null ? "" : message;
        boolean noWorkload = completed == null && total == null && unit == null;
        boolean completeWorkload = completed != null && total != null && unit != null;
        boolean countingWorkload = completed != null && total == null && unit != null && completed >= 0;
        if (!noWorkload && !completeWorkload && !countingWorkload) {
            throw new IllegalArgumentException("任务阶段工作量必须完整提供");
        }
        if (completeWorkload
                && (total < 1 || completed < 0 || completed > total)) {
            throw new IllegalArgumentException("任务阶段工作量不合法");
        }
    }

    public static ProgressStage indeterminate(String code, String message) {
        return new ProgressStage(code, message, null, null, null);
    }

    public static ProgressStage determinate(
            String code,
            String message,
            long completed,
            long total,
            ProgressUnit unit) {
        return new ProgressStage(code, message, completed, total, unit);
    }

    public boolean determinate() {
        return total != null;
    }

    /** 扫描总量未知时只报告已扫描条目数，不计算百分比。 */
    public static ProgressStage counting(String code, String message, long completed, ProgressUnit unit) {
        return new ProgressStage(code, message, completed, null, unit);
    }

    public Integer percent() {
        if (!determinate()) {
            return null;
        }
        return (int) Math.min(100, Math.floor((double) completed * 100 / total));
    }
}
