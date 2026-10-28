package org.ocean.admin.gis.progress;

import java.util.Objects;
import java.util.List;

/** 对实时任务状态的一次明确修改。 */
public sealed interface ProgressUpdate permits
        ProgressUpdate.StageChanged, // 阶段变更
        ProgressUpdate.OverallProgressChanged, // 总体进度变更
        ProgressUpdate.ResultChanged, // 结果变更
        ProgressUpdate.MetricChanged,
        ProgressUpdate.SummaryChanged,
        ProgressUpdate.ActiveFilesChanged { // 当前并行解析的文件

    static ProgressUpdate activeFilesChanged(List<ProgressSnapshot.FileWork> files) { return new ActiveFilesChanged(files); }

    record ActiveFilesChanged(List<ProgressSnapshot.FileWork> files) implements ProgressUpdate {
        public ActiveFilesChanged { files = List.copyOf(files); }
    }

    static ProgressUpdate metric(String code, String name, long value, Long total, ProgressUnit unit) {
        return new MetricChanged(new ProgressSnapshot.Metric(code, name, value, total, unit));
    }

    static ProgressUpdate summaryChanged(ProgressSummary summary) { return new SummaryChanged(summary); }

    record MetricChanged(ProgressSnapshot.Metric metric) implements ProgressUpdate {
        public MetricChanged { Objects.requireNonNull(metric); }
    }

    record SummaryChanged(ProgressSummary summary) implements ProgressUpdate {
        public SummaryChanged { Objects.requireNonNull(summary); }
    }

    static ProgressUpdate stageChanged(ProgressStage stage) {
        return new StageChanged(stage);
    }

    static ProgressUpdate overallProgressChanged(Integer percent) {
        return new OverallProgressChanged(percent);
    }

    static ProgressUpdate resultChanged(long succeeded, long failed) {
        return new ResultChanged(succeeded, failed);
    }

    record StageChanged(ProgressStage stage) implements ProgressUpdate {
        public StageChanged {
            Objects.requireNonNull(stage, "任务阶段不能为空");
        }
    }

    record OverallProgressChanged(Integer percent) implements ProgressUpdate {
        public OverallProgressChanged {
            if (percent != null && (percent < 0 || percent > 100)) {
                throw new IllegalArgumentException("任务整体进度必须在0到100之间");
            }
        }
    }

    record ResultChanged(long succeeded, long failed) implements ProgressUpdate {
        public ResultChanged {
            if (succeeded < 0 || failed < 0) {
                throw new IllegalArgumentException("任务处理结果计数不能为负数");
            }
        }
    }

}
