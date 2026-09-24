package org.ocean.admin.gis.processing;

/** 切片引擎上报的结构化工作量进度。 */
public record GisProcessingProgress(
        ProgressMode mode,
        String phase,
        long completedUnits,
        long totalUnits,
        String message) {

    public GisProcessingProgress {
        if (mode == null) {
            throw new IllegalArgumentException("进度模式不能为空");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("进度阶段不能为空");
        }
        if (mode == ProgressMode.DETERMINATE) {
            if (totalUnits < 1) {
                throw new IllegalArgumentException("可确定进度的总工作量必须大于0");
            }
            if (completedUnits < 0 || completedUnits > totalUnits) {
                throw new IllegalArgumentException("已完成工作量不合法");
            }
        } else {
            completedUnits = 0;
            totalUnits = 0;
        }
        message = message == null ? "" : message;
    }

    public static GisProcessingProgress indeterminate(String phase, String message) {
        return new GisProcessingProgress(ProgressMode.INDETERMINATE, phase, 0, 0, message);
    }

    public static GisProcessingProgress determinate(
            String phase, long completedUnits, long totalUnits, String message) {
        return new GisProcessingProgress(
                ProgressMode.DETERMINATE, phase, completedUnits, totalUnits, message);
    }

    /** 处理阶段仅使用0-99，100由任务成功终态设置。 */
    public int processingPercent() {
        if (mode != ProgressMode.DETERMINATE || totalUnits <= 0) {
            return 0;
        }
        return (int) Math.min(99,
                Math.floor((double) completedUnits * 100 / totalUnits));
    }

    public enum ProgressMode {
        INDETERMINATE,
        DETERMINATE
    }
}
