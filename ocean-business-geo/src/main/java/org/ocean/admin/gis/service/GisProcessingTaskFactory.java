package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.ocean.admin.gis.entity.GisProcessingTask;

import java.time.LocalDateTime;

/** 初始化尚未运行的 GIS 处理任务。 */
public final class GisProcessingTaskFactory {
    private GisProcessingTaskFactory() {
    }

    public static GisProcessingTask createQueued(String taskNo, String taskName, long totalCount,
            String currentStage) {
        LocalDateTime now = LocalDateTime.now();
        GisProcessingTask task = new GisProcessingTask();
        task.setId(IdWorker.getId());
        task.setTaskNo(taskNo);
        task.setTaskName(taskName);
        task.setPriority(0);
        task.setTotalCount(totalCount);
        task.setCompletedCount(0L);
        task.setFailedCount(0L);
        task.setTaskStatus("QUEUED");
        task.setCurrentStage(currentStage);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        return task;
    }
}
