package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 初始化尚未运行的 GIS 处理任务。 */
public final class GisProcessingTaskFactory {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private GisProcessingTaskFactory() {
    }

    public static GisProcessingTask queued(String taskNo, String taskName, long totalCount,
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

    public static String generateTaskNo(GisProcessingType type) {
        return generateTaskNo(type, LocalDateTime.now());
    }

    public static String generateTaskNo(GisProcessingType type, LocalDateTime timestamp) {
        if (type == null) {
            throw new IllegalArgumentException("GIS 处理类型不能为空");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("GIS 任务时间戳不能为空");
        }
        String prefix = type.name().toLowerCase(Locale.ROOT);
        return prefix + "_" + timestamp.format(TIMESTAMP) + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
