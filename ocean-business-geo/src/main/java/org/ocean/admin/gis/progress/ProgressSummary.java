package org.ocean.admin.gis.progress;

import java.util.Objects;

/** 整个任务需要处理的对象及其最终处理结果。 */
public record ProgressSummary(
        long total, // 总对象数
        long succeeded, // 成功处理的对象数
        long failed, // 失败处理的对象数
        long skipped, // 明确跳过的对象数
        ProgressUnit unit) {

    public ProgressSummary {
        if (total < 0) {
            throw new IllegalArgumentException("任务处理对象总数不能为负数");
        }
        if (succeeded < 0 || failed < 0 || skipped < 0 || succeeded + failed + skipped > total) {
            throw new IllegalArgumentException("任务处理结果计数不合法");
        }
        Objects.requireNonNull(unit, "任务处理对象单位不能为空");
    }

    public ProgressSummary(long total, long succeeded, long failed, ProgressUnit unit) {
        this(total, succeeded, failed, 0, unit);
    }

    public long processed() {
        return succeeded + failed + skipped;
    }

    public ProgressSummary withCounts(long newSucceeded, long newFailed) {
        return new ProgressSummary(total, newSucceeded, newFailed, skipped, unit);
    }

}
