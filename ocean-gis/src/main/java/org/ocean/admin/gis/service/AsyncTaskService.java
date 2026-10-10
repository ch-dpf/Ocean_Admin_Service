package org.ocean.admin.gis.service;

import org.ocean.admin.gis.dto.TaskProgressMessage;
import org.ocean.admin.gis.dto.TaskProgressModel;
import org.ocean.admin.gis.websocket.TaskWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 异步任务管理服务
 * 用于跟踪和管理后台异步任务的进度
 */
@Slf4j
@Service
public class AsyncTaskService {

    private static final String TASK_REDIS_KEY_PREFIX = "ocean-admin:async-task:";
    private static final Duration RUNNING_TASK_REDIS_TTL = Duration.ofHours(6);

    // 存储所有正在进行的任务
    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    private final TaskWebSocketHandler webSocketHandler;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AsyncTaskService(TaskWebSocketHandler webSocketHandler,
                            @Nullable StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 从Redis恢复任务
     */
    @PostConstruct
    public void restoreTasksFromRedis() {
        if (redisTemplate == null) {
            log.warn("AsyncTaskService 未启用Redis持久化（StringRedisTemplate不可用）");
            return;
        }
        try {
            Set<String> keys = redisTemplate.keys(TASK_REDIS_KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            int restored = 0;
            for (String key : keys) {
                String raw = redisTemplate.opsForValue().get(key);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                TaskInfo info = objectMapper.readValue(raw, TaskInfo.class);
                if (info.getTaskId() == null || info.getTaskId().isBlank()) {
                    continue;
                }
                taskMap.put(info.getTaskId(), info);
                restored++;
            }
            if (restored > 0) {
                log.info("AsyncTaskService 已从Redis恢复任务: {} 个", restored);
            }
        } catch (Exception e) {
            log.warn("从Redis恢复任务失败: {}", e.getMessage());
        }
    }

    /**
     * 使用业务侧稳定任务编号注册进度任务。
     */
    public String registerTask(String taskId, String taskName, int totalCount, String taskType) {
        return registerTask(taskId, taskName, totalCount, taskType, 0, 0);
    }

    public String registerTask(
            String taskId,
            String taskName,
            int totalCount,
            String taskType,
            int completedCount,
            int failedCount) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        if (totalCount < 1) {
            throw new IllegalArgumentException("任务总数必须大于0");
        }
        if (completedCount < 0 || failedCount < 0 || completedCount + failedCount > totalCount) {
            throw new IllegalArgumentException("任务初始计数不合法");
        }
        initializeTask(taskId, taskName, totalCount, taskType, completedCount, failedCount);
        return taskId;
    }

    private void initializeTask(String taskId,
                                String taskName,
                                int totalCount,
                                String taskType,
                                int completedCount,
                                int failedCount) {
        TaskInfo taskInfo = new TaskInfo();
        taskInfo.setTaskId(taskId);
        taskInfo.setTaskName(taskName);
        taskInfo.setTotalCount(totalCount);
        taskInfo.setCompletedCount(completedCount);
        taskInfo.setFailedCount(failedCount);
        taskInfo.setStatus("running");
        taskInfo.setStartTime(LocalDateTime.now());
        taskInfo.setTaskType(taskType != null ? taskType : "GENERAL");
        taskInfo.setStage("queued");
        taskInfo.setMessage("任务已创建");
        taskInfo.setManualProgress(null);
        taskInfo.setProgressMode(TaskProgressModel.ProgressMode.INDETERMINATE.name());

        taskMap.put(taskId, taskInfo);
        persistTask(taskInfo);
        log.info("创建任务: taskId={}, taskName={}, totalCount={}", taskId, taskName, totalCount);
        
    }

    /**
     * 任务按正常流程处理完毕
     * 根据已累计的成功、失败数量结束任务。
     *
     * 如果所有任务都处理成功，则标记为完成。
     * 如果没有任务处理成功，则标记为失败。
     * 如果有任务处理成功但存在失败任务，则标记为部分失败。
     */
    public void finalizeTaskResult(String taskId, String message) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null) {
            return;
        }
        synchronized (taskInfo) {
            if (taskInfo.getCompletedCount() + taskInfo.getFailedCount()
                    != taskInfo.getTotalCount()) {
                throw new IllegalStateException("任务仍有未处理项，不能进入终态");
            }
            taskInfo.setManualProgress(100);
            taskInfo.setProgressMode(TaskProgressModel.ProgressMode.DETERMINATE.name());
            taskInfo.setStage("completed");
            taskInfo.setMessage(message != null && !message.isBlank() ? message : "任务结束");
            if (taskInfo.getFailedCount() == 0) {
                taskInfo.setStatus("completed");
            } else if (taskInfo.getCompletedCount() == 0) {
                taskInfo.setStatus("failed");
                taskInfo.setStage("failed");
            } else {
                taskInfo.setStatus("partial_failed");
                taskInfo.setStage("partial_failed");
            }
            taskInfo.setEndTime(LocalDateTime.now());
        }
        pushTerminalProgress(taskId);
    }

    /**
     * 任务因系统异常、入库失败、引擎失败等原因被迫整体终止
     * 将任务标记为整体失败。
     */
    public void finalizeTaskFailure(String taskId, String message) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null) {
            return;
        }
        synchronized (taskInfo) {
            int lastProgress = taskInfo.getProgress();
            int unprocessed = taskInfo.getTotalCount()
                    - taskInfo.getCompletedCount()
                    - taskInfo.getFailedCount();
            if (unprocessed > 0) {
                taskInfo.setFailedCount(taskInfo.getFailedCount() + unprocessed);
            }
            taskInfo.setManualProgress(lastProgress);
            taskInfo.setStatus("failed");
            taskInfo.setStage("failed");
            taskInfo.setMessage(message != null && !message.isBlank() ? message : "任务失败");
            taskInfo.setEndTime(LocalDateTime.now());
        }
        pushTerminalProgress(taskId);
    }

    /** 接收文件上传与 GIS 处理引擎共用的进度更新。 */
    public void updateProgress(String taskId, TaskProgressModel progress) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null || progress == null) {
            return;
        }
        synchronized (taskInfo) {
            if (isTerminal(taskInfo.getStatus())) {
                return;
            }
            switch (progress.updateType()) {
                case PERCENTAGE -> applyPercentage(taskInfo, progress);
                case COUNTS -> applyCounts(taskInfo, progress);
                case ITEM_RESULT -> applyItemResult(taskInfo, progress);
                case WORKLOAD -> applyWorkload(taskInfo, progress);
            }
            taskInfo.setStatus("running");
        }
        pushProgress(taskId);
        log.debug("推送任务进度: taskId={}, stage={}, progress={}%, message={}", taskId,
                taskInfo.getStage(), taskInfo.getProgress(), taskInfo.getMessage());
    }

    private void applyPercentage(TaskInfo taskInfo, TaskProgressModel progress) {
        taskInfo.setManualProgress(Math.max(taskInfo.getProgress(), progress.progress()));
        taskInfo.setProgressMode(TaskProgressModel.ProgressMode.DETERMINATE.name());
        taskInfo.setStage(progress.stage());
        taskInfo.setMessage(progress.message());
    }

    private void applyCounts(TaskInfo taskInfo, TaskProgressModel progress) {
        if (progress.completedCount() + progress.failedCount() > taskInfo.getTotalCount()) {
            throw new IllegalArgumentException("任务进度计数不合法");
        }
        taskInfo.setCompletedCount(progress.completedCount());
        taskInfo.setFailedCount(progress.failedCount());
        taskInfo.setManualProgress(progress.progress());
        taskInfo.setProgressMode(TaskProgressModel.ProgressMode.DETERMINATE.name());
        taskInfo.setStage(progress.stage());
        taskInfo.setMessage(progress.message());
    }

    private void applyItemResult(TaskInfo taskInfo, TaskProgressModel progress) {
        int completedCount = taskInfo.getCompletedCount() + (progress.success() ? 1 : 0);
        int failedCount = taskInfo.getFailedCount() + (progress.success() ? 0 : 1);
        if (completedCount + failedCount > taskInfo.getTotalCount()) {
            throw new IllegalStateException("任务处理结果数量超过任务总数");
        }
        taskInfo.setCompletedCount(completedCount);
        taskInfo.setFailedCount(failedCount);
        if (!taskInfo.isProcessingProgressManaged()) {
            taskInfo.setManualProgress(null);
        }
        taskInfo.setProgressMode(TaskProgressModel.ProgressMode.DETERMINATE.name());
    }

    private void applyWorkload(TaskInfo taskInfo, TaskProgressModel progress) {
        taskInfo.setStage(progress.stage());
        taskInfo.setMessage(progress.message());
        taskInfo.setProgressMode(progress.progressMode().name());
        taskInfo.setProcessingProgressManaged(true);
        if (progress.progressMode() == TaskProgressModel.ProgressMode.DETERMINATE) {
            taskInfo.setCompletedUnits(progress.completedUnits());
            taskInfo.setTotalUnits(progress.totalUnits());
            taskInfo.setManualProgress(Math.max(
                    taskInfo.getProgress(), progress.processingPercent()));
        } else {
            taskInfo.setCompletedUnits(null);
            taskInfo.setTotalUnits(null);
        }
    }

    /**
     * 推送任务进度到前端
     */
    private void pushProgress(String taskId) {
        pushProgress(taskId, "task_update");
    }

    private void pushProgress(String taskId, String eventType) {
        pushProgress(taskId, eventType, true);
    }

    private void pushTerminalProgress(String taskId) {
        pushProgress(taskId, "task_update", false);
        deleteTaskFromRedis(taskId);
    }

    private void pushProgress(String taskId, String eventType, boolean persist) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo != null) {
            TaskProgressMessage message = new TaskProgressMessage();
            synchronized (taskInfo) {
                taskInfo.setVersion(taskInfo.getVersion() + 1);
                if (persist) {
                    persistTask(taskInfo);
                }
                message.setEventType(eventType == null || eventType.isBlank() ? "task_update" : eventType);
                message.setTaskId(taskInfo.getTaskId());
                message.setTaskName(taskInfo.getTaskName());
                message.setTotalCount(taskInfo.getTotalCount());
                message.setCompletedCount(taskInfo.getCompletedCount());
                message.setFailedCount(taskInfo.getFailedCount());
                message.setStatus(taskInfo.getStatus());
                message.setProgress(taskInfo.getProgress());
                message.setTaskType(taskInfo.getTaskType());
                message.setStage(taskInfo.getStage());
                message.setMessage(taskInfo.getMessage());
                message.setProgressMode(taskInfo.getProgressMode());
                message.setCompletedUnits(taskInfo.getCompletedUnits());
                message.setTotalUnits(taskInfo.getTotalUnits());
                message.setVersion(taskInfo.getVersion());
                message.setDone(isTerminal(taskInfo.getStatus()));
                message.setTimestamp(System.currentTimeMillis());
            }
            
            // 通过 WebSocket 广播
            webSocketHandler.broadcastTaskProgress(message);
            log.debug("推送任务进度: taskId={}, progress={}%", taskId, message.getProgress());
        }
    }

    /**
     * 获取所有运行中的任务
     */
    public List<TaskInfo> getRunningTasks() {
        return taskMap.values().stream()
                .filter(task -> "running".equals(task.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有任务（包括已完成）
     */
    public List<TaskInfo> getAllTasks() {
        return taskMap.values().stream()
                .sorted((t1, t2) -> t2.getStartTime().compareTo(t1.getStartTime()))
                .collect(Collectors.toList());
    }

    /**
     * 获取任务详情
     */
    public TaskInfo getTaskInfo(String taskId) {
        return taskMap.get(taskId);
    }

    /**
     * 清理已完成的任务
     */
    public void cleanupCompletedTasks() {
        taskMap.entrySet().removeIf(entry -> {
            boolean completed = isTerminal(entry.getValue().getStatus());
            if (completed) {
                deleteTaskFromRedis(entry.getKey());
            }
            return completed;
        });
    }

    private void persistTask(TaskInfo taskInfo) {
        if (redisTemplate == null || taskInfo == null || taskInfo.getTaskId() == null) {
            return;
        }
        try {
            String key = TASK_REDIS_KEY_PREFIX + taskInfo.getTaskId();
            String json = objectMapper.writeValueAsString(taskInfo);
            redisTemplate.opsForValue().set(key, json, RUNNING_TASK_REDIS_TTL);
        } catch (Exception e) {
            log.debug("任务进度写入Redis失败: taskId={}, error={}", taskInfo.getTaskId(), e.getMessage());
        }
    }

    private void deleteTaskFromRedis(String taskId) {
        if (redisTemplate == null || taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(TASK_REDIS_KEY_PREFIX + taskId);
        } catch (Exception e) {
            log.debug("删除Redis任务快照失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private boolean isTerminal(String status) {
        return "completed".equals(status)
                || "partial_failed".equals(status)
                || "failed".equals(status);
    }

    /**
     * 任务信息
     */
    @Data
    public static class TaskInfo {
        private String taskId; // 任务id
        private String taskName; // 任务名称
        private int totalCount; // 总数量
        private int completedCount; // 完成数量
        private int failedCount; // 失败数量
        private String status; // 状态 running, completed, partial_failed, failed
        private LocalDateTime startTime; // 开始时间
        private LocalDateTime endTime; // 结束时间
        private String taskType; // 任务类型
        private String stage; // 当前阶段
        private String message; // 消息
        private Integer manualProgress; // 手工进度
        private String progressMode; // 进度模式
        private Long completedUnits; // 完成单位
        private Long totalUnits; // 总单位
        private long version; // 版本号
        private boolean processingProgressManaged; // 进度管理由系统处理

        /**
         * 获取进度百分比
         * 如果手动进度不为空，则返回手动进度
         * 否则返回根据完成和失败数量计算的进度百分比，总进度为100%
         */
        public int getProgress() {
            if (manualProgress != null) {
                return Math.max(0, Math.min(100, manualProgress));
            }
            if (totalCount == 0) return 0;
            return (int) (((completedCount + failedCount) * 100.0) / totalCount);
        }
    }
}
