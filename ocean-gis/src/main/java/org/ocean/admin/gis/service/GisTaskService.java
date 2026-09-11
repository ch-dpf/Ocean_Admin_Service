package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** GIS 持久化任务服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GisTaskService {

    private final GisTaskMapper gisTaskMapper;

    public void insert(GisTask task) {
        if (gisTaskMapper.insert(task) != 1) {
            throw new IllegalStateException("GIS任务创建失败");
        }
    }

    public GisTask getRequired(Long taskId) {
        GisTask task = gisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("GIS任务不存在或已删除: " + taskId);
        }
        return task;
    }

    public void markRunning(Long taskId) {
        int updated = gisTaskMapper.update(null,
                new LambdaUpdateWrapper<GisTask>()
                        .eq(GisTask::getId, taskId)
                        .eq(GisTask::getTaskStatus, "QUEUED")
                        .set(GisTask::getTaskStatus, "RUNNING")
                        .set(GisTask::getCurrentStage, "PROCESSING")
                        .set(GisTask::getStartTime, LocalDateTime.now())
                        .set(GisTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("GIS任务无法进入运行状态: " + taskId);
        }
    }

    public void incrementCompleted(Long taskId) {
        increment(taskId, "completed_count = completed_count + 1", true);
    }

    public void incrementFailed(Long taskId) {
        increment(taskId, "failed_count = failed_count + 1", false);
    }

    private void increment(Long taskId, String countSql, boolean success) {
        int updated = gisTaskMapper.update(null,
                new LambdaUpdateWrapper<GisTask>()
                        .eq(GisTask::getId, taskId)
                        .eq(GisTask::getTaskStatus, "RUNNING")
                        .setSql(countSql)
                        .set(GisTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("GIS任务进度更新失败: " + taskId + ", success=" + success);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public GisTask finish(Long taskId) {
        GisTask task = getRequired(taskId);
        String status;
        if (task.getFailedCount() == 0) {
            status = "COMPLETED";
        } else if (task.getCompletedCount() == 0) {
            status = "FAILED";
        } else {
            status = "PARTIAL_FAILED";
        }

        int updated = gisTaskMapper.update(null,
                new LambdaUpdateWrapper<GisTask>()
                        .eq(GisTask::getId, taskId)
                        .eq(GisTask::getTaskStatus, "RUNNING")
                        .set(GisTask::getTaskStatus, status)
                        .set(GisTask::getCurrentStage, "FINISHED")
                        .set(GisTask::getFinishTime, LocalDateTime.now())
                        .set(GisTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("GIS任务结束状态更新失败: " + taskId);
        }
        task.setTaskStatus(status);
        return task;
    }

    public void markFailed(Long taskId, String errorMessage) {
        gisTaskMapper.update(null,
                new LambdaUpdateWrapper<GisTask>()
                        .eq(GisTask::getId, taskId)
                        .notIn(GisTask::getTaskStatus, "COMPLETED", "PARTIAL_FAILED", "FAILED")
                        .setSql("failed_count = GREATEST(failed_count, total_count - completed_count)")
                        .set(GisTask::getTaskStatus, "FAILED")
                        .set(GisTask::getCurrentStage, "FAILED")
                        .set(GisTask::getErrorMessage, abbreviate(errorMessage, 1000))
                        .set(GisTask::getFinishTime, LocalDateTime.now())
                        .set(GisTask::getUpdateTime, LocalDateTime.now()));
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
