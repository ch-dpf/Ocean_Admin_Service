package org.ocean.admin.gis.service;

import org.ocean.admin.gis.entity.GisTask;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 统一初始化尚未运行的 GIS 任务；业务字段由各任务服务补充。 */
public final class GisTaskFactory {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private GisTaskFactory() {
    }

    public static GisTask queued(String taskNo, String taskName, long taskType,
            long totalCount, String currentStage) {
        LocalDateTime now = LocalDateTime.now();
        GisTask task = new GisTask();
        task.setTaskNo(taskNo);
        task.setTaskName(taskName);
        task.setTaskType(taskType);
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

    public static String generateTaskNo(String prefix) {
        return generateTaskNo(prefix, LocalDateTime.now());
    }

    public static String generateTaskNo(String prefix, LocalDateTime timestamp) {
        if (prefix == null || !prefix.matches("GIS_[A-Z0-9_]+")) {
            throw new IllegalArgumentException("非法 GIS 任务编号前缀");
        }
        return prefix + "_" + timestamp.format(TIMESTAMP) + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
