package org.ocean.admin.gis.dto;

import java.util.Objects;

/** 对实时任务状态的一次明确修改。 */
public sealed interface TaskProgressUpdate permits
        TaskProgressUpdate.StageChanged, // 阶段变更
        TaskProgressUpdate.OverallProgressChanged, // 总体进度变更
        TaskProgressUpdate.ResultChanged, // 结果变更
        TaskProgressUpdate.ItemFinished { // 单项完成

    static TaskProgressUpdate stageChanged(TaskStage stage) {
        return new StageChanged(stage);
    }

    static TaskProgressUpdate overallProgressChanged(int percent) {
        return new OverallProgressChanged(percent);
    }

    static TaskProgressUpdate resultChanged(long succeeded, long failed) {
        return new ResultChanged(succeeded, failed);
    }

    static TaskProgressUpdate itemFinished(boolean succeeded) {
        return new ItemFinished(succeeded);
    }

    record StageChanged(TaskStage stage) implements TaskProgressUpdate {
        public StageChanged {
            Objects.requireNonNull(stage, "任务阶段不能为空");
        }
    }

    record OverallProgressChanged(int percent) implements TaskProgressUpdate {
        public OverallProgressChanged {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("任务整体进度必须在0到100之间");
            }
        }
    }

    record ResultChanged(long succeeded, long failed) implements TaskProgressUpdate {
        public ResultChanged {
            if (succeeded < 0 || failed < 0) {
                throw new IllegalArgumentException("任务处理结果计数不能为负数");
            }
        }
    }

    record ItemFinished(boolean succeeded) implements TaskProgressUpdate {
    }
}
