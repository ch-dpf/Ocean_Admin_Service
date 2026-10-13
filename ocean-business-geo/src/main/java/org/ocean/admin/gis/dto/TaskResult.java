package org.ocean.admin.gis.dto;

import java.util.Objects;

/** 整个任务需要处理的对象及其最终处理结果。 */
public record TaskResult(
        long total, // 总对象数
        long succeeded, // 成功处理的对象数
        long failed, // 失败处理的对象数
        TaskProgressUnit unit) {

    public TaskResult {
        if (total < 1) {
            throw new IllegalArgumentException("任务处理对象总数必须大于0");
        }
        if (succeeded < 0 || failed < 0 || succeeded + failed > total) {
            throw new IllegalArgumentException("任务处理结果计数不合法");
        }
        Objects.requireNonNull(unit, "任务处理对象单位不能为空");
    }

    public long processed() {
        return succeeded + failed;
    }

    public int percent() {
        return (int) Math.floor((double) processed() * 100 / total);
    }

    public TaskResult withCounts(long newSucceeded, long newFailed) {
        return new TaskResult(total, newSucceeded, newFailed, unit);
    }

    public TaskResult finishItem(boolean success) {
        return success
                ? withCounts(succeeded + 1, failed)
                : withCounts(succeeded, failed + 1);
    }

    public TaskResult failRemaining() {
        return withCounts(succeeded, failed + total - processed());
    }
}
