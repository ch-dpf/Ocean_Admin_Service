package org.ocean.admin.gis.dto;

/** 任务进度模型 */
public record TaskProgressModel(
        UpdateType updateType,
        ProgressMode progressMode,
        String stage,
        String message,
        Integer progress,
        Integer completedCount,
        Integer failedCount,
        Boolean success,
        Long completedUnits,
        Long totalUnits) {

    public TaskProgressModel {
        if (updateType == null || progressMode == null) {
            throw new IllegalArgumentException("进度更新类型和进度模式不能为空");
        }
        message = message == null ? "" : message;
        switch (updateType) {
            case PERCENTAGE -> {
                requireStage(stage);
                requirePercentage(progress);
            }
            case COUNTS -> {
                requireStage(stage);
                requirePercentage(progress);
                if (completedCount == null || completedCount < 0
                        || failedCount == null || failedCount < 0) {
                    throw new IllegalArgumentException("任务进度计数不能为负数");
                }
            }
            case ITEM_RESULT -> {
                if (success == null) {
                    throw new IllegalArgumentException("处理项结果不能为空");
                }
            }
            case WORKLOAD -> {
                requireStage(stage);
                if (progressMode == ProgressMode.DETERMINATE) {
                    if (totalUnits == null || totalUnits < 1) {
                        throw new IllegalArgumentException("可确定进度的总工作量必须大于0");
                    }
                    if (completedUnits == null || completedUnits < 0
                            || completedUnits > totalUnits) {
                        throw new IllegalArgumentException("已完成工作量不合法");
                    }
                }
            }
        }
    }

    public static TaskProgressModel percentage(
            int progress, String stage, String message) {
        return new TaskProgressModel(UpdateType.PERCENTAGE, ProgressMode.DETERMINATE,
                stage, message, progress, null, null, null, null, null);
    }

    public static TaskProgressModel counts(
            int completedCount, int failedCount, int progress,
            String stage, String message) {
        return new TaskProgressModel(UpdateType.COUNTS, ProgressMode.DETERMINATE,
                stage, message, progress, completedCount, failedCount,
                null, null, null);
    }

    public static TaskProgressModel itemResult(boolean success) {
        return new TaskProgressModel(UpdateType.ITEM_RESULT, ProgressMode.DETERMINATE,
                null, "", null, null, null, success, null, null);
    }

    public static TaskProgressModel indeterminate(String stage, String message) {
        return new TaskProgressModel(UpdateType.WORKLOAD, ProgressMode.INDETERMINATE,
                stage, message, null, null, null, null, 0L, 0L);
    }

    public static TaskProgressModel workload(
            String stage, long completedUnits, long totalUnits, String message) {
        return new TaskProgressModel(UpdateType.WORKLOAD, ProgressMode.DETERMINATE,
                stage, message, null, null, null, null, completedUnits, totalUnits);
    }

    /** 处理阶段只使用0到99，100由任务成功终态设置。 */
    public int processingPercent() {
        if (updateType != UpdateType.WORKLOAD
                || progressMode != ProgressMode.DETERMINATE
                || totalUnits == null || totalUnits <= 0) {
            return 0;
        }
        return (int) Math.min(99,
                Math.floor((double) completedUnits * 100 / totalUnits));
    }

    private static void requireStage(String stage) {
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("进度阶段不能为空");
        }
    }

    private static void requirePercentage(Integer progress) {
        if (progress == null || progress < 0 || progress > 100) {
            throw new IllegalArgumentException("进度百分比必须在0到100之间");
        }
    }

    public enum UpdateType {
        PERCENTAGE, // 百分比
        COUNTS, // 计数
        ITEM_RESULT, // 项结果
        WORKLOAD // 工作量
    }

    public enum ProgressMode {
        INDETERMINATE, // 不确定的进度模式
        DETERMINATE // 确定的进度模式
    }
}
