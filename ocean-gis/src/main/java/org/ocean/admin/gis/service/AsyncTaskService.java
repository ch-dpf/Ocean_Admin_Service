package org.ocean.admin.gis.service;

import org.ocean.admin.gis.dto.TaskProgressMessage;
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
    private static final Duration TASK_REDIS_TTL = Duration.ofHours(24);

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
        initializeTask(taskId, taskName, totalCount, taskType, null, null, completedCount, failedCount);
        return taskId;
    }

    private void initializeTask(String taskId,
                                String taskName,
                                int totalCount,
                                String taskType,
                                String fileType,
                                Long fileId,
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
        taskInfo.setFileType(fileType != null ? fileType.toUpperCase() : null);
        taskInfo.setFileId(fileId);
        taskInfo.setStage("queued");
        taskInfo.setMessage("任务已创建");
        taskInfo.setManualProgress(null);

        taskMap.put(taskId, taskInfo);
        persistTask(taskInfo);
        log.info("创建任务: taskId={}, taskName={}, totalCount={}", taskId, taskName, totalCount);
        
    }

    /**
     * 按百分比更新任务进度（用于长任务实时上报）
     */
    public void updateTaskProgress(String taskId,
                                   int progress,
                                   String stage,
                                   String message,
                                   String status,
                                   boolean done) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null) {
            return;
        }

        int normalizedProgress = Math.max(0, Math.min(100, progress));
        taskInfo.setManualProgress(normalizedProgress);
        if (stage != null) {
            taskInfo.setStage(stage);
        }
        if (message != null) {
            taskInfo.setMessage(message);
        }
        if (status != null && !status.isEmpty()) {
            taskInfo.setStatus(status);
        }

        if (done) {
            taskInfo.setCompletedCount(taskInfo.getTotalCount());
            taskInfo.setFailedCount(0);
            taskInfo.setStatus("completed");
            taskInfo.setEndTime(LocalDateTime.now());
        }

        pushProgress(taskId);
    }

    /**
     * 根据已累计的成功、失败数量结束任务。
     */
    public void finalizeTaskResult(String taskId, String message) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null) {
            return;
        }
        synchronized (taskInfo) {
            taskInfo.setManualProgress(100);
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
        pushProgress(taskId);
    }

    /** 将任务标记为整体失败。 */
    public void finalizeTaskFailure(String taskId, String message) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null) {
            return;
        }
        synchronized (taskInfo) {
            int unprocessed = taskInfo.getTotalCount()
                    - taskInfo.getCompletedCount()
                    - taskInfo.getFailedCount();
            if (unprocessed > 0) {
                taskInfo.setFailedCount(taskInfo.getFailedCount() + unprocessed);
            }
            taskInfo.setManualProgress(100);
            taskInfo.setStatus("failed");
            taskInfo.setStage("failed");
            taskInfo.setMessage(message != null && !message.isBlank() ? message : "任务失败");
            taskInfo.setEndTime(LocalDateTime.now());
        }
        pushProgress(taskId);
    }

    /**
     * 更新任务进度
     */
    public void updateProgress(String taskId, boolean success) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo != null) {
            synchronized (taskInfo) {
                if (isTerminal(taskInfo.getStatus())) {
                    return;
                }
                taskInfo.setManualProgress(null);
                if (success) {
                    taskInfo.setCompletedCount(taskInfo.getCompletedCount() + 1);
                } else {
                    taskInfo.setFailedCount(taskInfo.getFailedCount() + 1);
                }
            }
            pushProgress(taskId);
            log.debug("📤 推送任务进度: taskId={}, status={}, progress={}%", taskId, taskInfo.getStatus(), taskInfo.getProgress());
        }
    }

    public void updateProgressCounts(
            String taskId,
            int completedCount,
            int failedCount,
            int progress,
            String stage,
            String message) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null) {
            return;
        }
        synchronized (taskInfo) {
            if (isTerminal(taskInfo.getStatus())) {
                return;
            }
            if (completedCount < 0 || failedCount < 0
                    || completedCount + failedCount > taskInfo.getTotalCount()) {
                throw new IllegalArgumentException("任务进度计数不合法");
            }
            taskInfo.setCompletedCount(completedCount);
            taskInfo.setFailedCount(failedCount);
            taskInfo.setManualProgress(Math.max(0, Math.min(100, progress)));
            taskInfo.setStage(stage);
            taskInfo.setMessage(message);
            taskInfo.setStatus("running");
        }
        pushProgress(taskId);
    }

    /**
     * 手动更新任务进度
     */
    public void updateProgress(String taskId, int progress, String stage, String message) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo == null) {
            return;
        }

        // 如果任务已经完成，不再更新和推送
        if (isTerminal(taskInfo.getStatus())) {
            return;
        }

        int safeProgress = Math.max(0, Math.min(100, progress));

        taskInfo.setManualProgress(safeProgress);
        taskInfo.setStage(stage);
        taskInfo.setMessage(message);

        if (!"running".equals(taskInfo.getStatus())) {
            taskInfo.setStatus("running");
        }

        pushProgress(taskId);

        log.debug("推送任务进度: taskId={}, stage={}, progress={}%, message={}",
                taskId, stage, taskInfo.getProgress(), message);
    }

    /**
     * 推送任务进度到前端
     */
    private void pushProgress(String taskId) {
        pushProgress(taskId, "task_update");
    }

    private void pushProgress(String taskId, String eventType) {
        TaskInfo taskInfo = taskMap.get(taskId);
        if (taskInfo != null) {
            persistTask(taskInfo);
            TaskProgressMessage message = new TaskProgressMessage();
            message.setEventType(eventType == null || eventType.isBlank() ? "task_update" : eventType);
            message.setTaskId(taskInfo.getTaskId());
            message.setTaskName(taskInfo.getTaskName());
            message.setTotalCount(taskInfo.getTotalCount());
            message.setCompletedCount(taskInfo.getCompletedCount());
            message.setFailedCount(taskInfo.getFailedCount());
            message.setStatus(taskInfo.getStatus());
            message.setProgress(taskInfo.getProgress());
            message.setTaskType(taskInfo.getTaskType());
            message.setFileType(taskInfo.getFileType());
            message.setFileId(taskInfo.getFileId());
            message.setStage(taskInfo.getStage());
            message.setMessage(taskInfo.getMessage());
            message.setDone(isTerminal(taskInfo.getStatus()));
            message.setTimestamp(System.currentTimeMillis());
            
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
            redisTemplate.opsForValue().set(key, json, TASK_REDIS_TTL);
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
        private String taskId;
        private String taskName;
        private int totalCount;
        private int completedCount;
        private int failedCount;
        private String status; // running, completed
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String taskType;
        private String fileType;
        private Long fileId;
        private String stage;
        private String message;
        private Integer manualProgress;

        /**
         * 获取进度百分比
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
