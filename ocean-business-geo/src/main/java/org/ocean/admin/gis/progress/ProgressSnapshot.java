package org.ocean.admin.gis.progress;

import org.ocean.admin.gis.dto.TaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 查询、缓存及进度事件共用的完整不可变快照。 */
public record ProgressSnapshot(String progressId, String correlationId, String type, String category,
        String title, TaskStatus status, Overall overall, ProgressStage stage,
        List<Metric> metrics, ProgressSummary summary, Resource resource, ErrorInfo error,
        LocalDateTime startedAt, LocalDateTime finishedAt, Revision revision) {
    public ProgressSnapshot {
        if (progressId == null || progressId.isBlank() || type == null || type.isBlank()
                || title == null || title.isBlank()) throw new IllegalArgumentException("进度身份、类型和标题不能为空");
        Objects.requireNonNull(status);
        Objects.requireNonNull(overall);
        Objects.requireNonNull(stage);
        Objects.requireNonNull(summary);
        Objects.requireNonNull(resource);
        Objects.requireNonNull(revision);
        metrics = List.copyOf(metrics);
        if (status.isTerminal() != (finishedAt != null))
            throw new IllegalArgumentException("进度终态与结束时间不一致");
    }

    public Handle handle() { return new Handle(progressId, revision.epoch()); }

    public static Reference reference(String id, String type, TaskStatus status) {
        return new Reference(id, type, status, "/api/gis/progress/" + id, "/api/gis/events/stream?topics=gis.progress");
    }

    public record Handle(String progressId, long epoch) {
        public Handle {
            if (progressId == null || progressId.isBlank() || epoch < 1)
                throw new IllegalArgumentException("进度句柄无效");
        }
    }

    public record Revision(long epoch, long sequence) {
        public Revision {
            if (epoch < 1 || sequence < 0) throw new IllegalArgumentException("进度版本无效");
        }
    }

    public record Reference(String progressId, String type, TaskStatus status,
                            String queryUrl, String eventsUrl) { }

    public enum Mode { DETERMINATE, INDETERMINATE }

    public record Overall(Mode mode, Integer percent) {
        public Overall {
            Objects.requireNonNull(mode);
            if ((mode == Mode.DETERMINATE) != (percent != null)
                    || (percent != null && (percent < 0 || percent > 100)))
                throw new IllegalArgumentException("整体进度模式与百分比不一致");
        }
        public static Overall of(Integer percent) {
            return new Overall(percent == null ? Mode.INDETERMINATE : Mode.DETERMINATE, percent);
        }
    }

    public record Metric(String code, String name, long value, Long total, ProgressUnit unit) {
        public Metric {
            if (code == null || code.isBlank() || name == null || name.isBlank() || value < 0
                    || (total != null && (total < 0 || value > total)))
                throw new IllegalArgumentException("进度指标无效");
            Objects.requireNonNull(unit);
        }
    }

    public record Resource(String type, String id, String detailUrl) { }
    public record ErrorInfo(String code, String message) { }

    public static ProgressStage normalizeStage(ProgressStage stage) {
        return new ProgressStage(stage.code().toUpperCase(Locale.ROOT), stage.message(),
                stage.completed(), stage.total(), stage.unit());
    }
}
