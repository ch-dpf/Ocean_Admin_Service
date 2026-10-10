package org.ocean.admin.gis.service;

import org.ocean.admin.gis.dto.TaskProgressMessage;
import org.ocean.admin.gis.dto.TaskInfo;
import org.ocean.admin.gis.dto.TaskProgressUnit;
import org.ocean.admin.gis.dto.TaskProgressUpdate;
import org.ocean.admin.gis.dto.TaskResult;
import org.ocean.admin.gis.dto.TaskStage;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.websocket.TaskWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
    private static final Duration RUNNING_TASK_INACTIVE_TIMEOUT = Duration.ofHours(6);

    // 存储所有正在进行的任务
    private final Map<String, TaskState> activeTaskMap = new ConcurrentHashMap<>();

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
                try {
                    String raw = redisTemplate.opsForValue().get(key);
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    TaskInfo info = objectMapper.readValue(raw, TaskInfo.class);
                    activeTaskMap.put(info.taskId(), TaskState.restore(info));
                    restored++;
                } catch (Exception e) {
                    log.warn("跳过无法恢复的异步任务快照: key={}, error={}",
                            key, e.getMessage());
                }
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
    public String registerTask(
            String taskId,
            String taskName,
            long total,
            String taskType,
            TaskProgressUnit resultUnit) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        if (total < 1) {
            throw new IllegalArgumentException("任务总数必须大于0");
        }
        initializeTask(taskId, taskName, total, taskType, resultUnit);
        return taskId;
    }

    private void initializeTask(String taskId,
                                String taskName,
                                long total,
                                String taskType,
                                TaskProgressUnit resultUnit) {
        TaskState taskState = TaskState.create(
                taskId,
                taskName,
                taskType != null && !taskType.isBlank() ? taskType : "GENERAL",
                total,
                resultUnit);
        TaskInfo initialSnapshot = taskState.snapshot();
        activeTaskMap.put(taskId, taskState);
        persistTask(initialSnapshot);
        log.info("创建任务: taskId={}, taskName={}, total={}", taskId, taskName, total);
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
        TaskState taskState = activeTaskMap.get(taskId);
        if (taskState == null) {
            return;
        }
        TaskInfo published;
        synchronized (taskState) {
            if (activeTaskMap.get(taskId) != taskState || taskState.status.isTerminal()) {
                return;
            }
            if (taskState.result.processed() != taskState.result.total()) {
                throw new IllegalStateException("任务仍有未处理项，不能进入终态");
            }
            taskState.overallPercent = 100;
            String finalMessage = message != null && !message.isBlank() ? message : "任务结束";
            if (taskState.result.failed() == 0) {
                taskState.status = TaskStatus.COMPLETED;
                taskState.currentStage = TaskStage.indeterminate("completed", finalMessage);
            } else if (taskState.result.succeeded() == 0) {
                taskState.status = TaskStatus.FAILED;
                taskState.currentStage = TaskStage.indeterminate("failed", finalMessage);
            } else {
                taskState.status = TaskStatus.PARTIAL_FAILED;
                taskState.currentStage = TaskStage.indeterminate("partial_failed", finalMessage);
            }
            taskState.endTime = LocalDateTime.now();
            taskState.version++;
            published = taskState.snapshot();
        }
        broadcastProgress(published, "task_update");
        activeTaskMap.remove(taskId, taskState);
        deleteTaskFromRedis(taskId);
    }

    /**
     * 任务因系统异常、入库失败、引擎失败等原因被迫整体终止
     * 将任务标记为整体失败。
     */
    public void finalizeTaskFailure(String taskId, String message) {
        TaskState taskState = activeTaskMap.get(taskId);
        if (taskState == null) {
            return;
        }
        TaskInfo published;
        synchronized (taskState) {
            if (activeTaskMap.get(taskId) != taskState || taskState.status.isTerminal()) {
                return;
            }
            taskState.result = taskState.result.failRemaining();
            taskState.status = TaskStatus.FAILED;
            taskState.currentStage = TaskStage.indeterminate(
                    "failed",
                    message != null && !message.isBlank() ? message : "任务失败");
            taskState.endTime = LocalDateTime.now();
            taskState.version++;
            published = taskState.snapshot();
        }
        broadcastProgress(published, "task_update");
        activeTaskMap.remove(taskId, taskState);
        deleteTaskFromRedis(taskId);
    }

    /*
     * 更新任务进度
     */
    public void updateProgress(String taskId, TaskProgressUpdate... updates) {
        TaskState taskState = activeTaskMap.get(taskId);
        if (taskState == null || updates == null || updates.length == 0) {
            return;
        }
        TaskInfo published;
        synchronized (taskState) {
            if (activeTaskMap.get(taskId) != taskState || taskState.status.isTerminal()) {
                return;
            }
            for (TaskProgressUpdate update : updates) {
                if (update == null) {
                    throw new IllegalArgumentException("任务进度更新不能为空");
                }
            }
            taskState.apply(updates);
            taskState.version++;
            published = taskState.snapshot();
            persistTask(published);
        }
        broadcastProgress(published, "task_update");
        log.debug("推送任务进度: taskId={}, stage={}, overallPercent={}, message={}",
                taskId,
                published.currentStage().code(),
                published.overallPercent(),
                published.currentStage().message());
    }

    /**
     * 广播任务进度更新
     */
    private void broadcastProgress(TaskInfo snapshot, String eventType) {
        TaskProgressMessage message = new TaskProgressMessage(
                eventType == null || eventType.isBlank() ? "task_update" : eventType,
                snapshot,
                System.currentTimeMillis());
        webSocketHandler.broadcastTaskProgress(message);
        log.debug("推送任务进度: taskId={}, overallPercent={}",
                snapshot.taskId(), snapshot.overallPercent());
    }

    /**
     * 获取所有运行中的任务
     */
    public List<TaskInfo> getRunningTasks() {
        return activeTaskMap.values().stream()
                .map(this::snapshot)
                .filter(task -> task.status() == TaskStatus.RUNNING)
                .collect(Collectors.toList());
    }

    /**
     * 获取任务详情
     */
    public TaskInfo getTaskInfo(String taskId) {
        TaskState taskState = activeTaskMap.get(taskId);
        return taskState == null ? null : snapshot(taskState);
    }

    /**
     * 手动清理超过活跃超时时间仍未更新的异常存在的实时任务
     */
    public int cleanupZombieTasks() {
        LocalDateTime inactiveBefore = LocalDateTime.now()
                .minus(RUNNING_TASK_INACTIVE_TIMEOUT);
        int cleanedCount = 0;
        for (Map.Entry<String, TaskState> entry : activeTaskMap.entrySet()) {
            TaskState taskState = entry.getValue();
            boolean removed = false;
            synchronized (taskState) {
                if (!taskState.status.isTerminal()
                        && taskState.lastActivityTime.isBefore(inactiveBefore)) {
                    removed = activeTaskMap.remove(entry.getKey(), taskState);
                }
            }
            if (removed) {
                deleteTaskFromRedis(entry.getKey());
                cleanedCount++;
            }
        }
        log.info("手动清理异常存在的实时任务完成: cleanedCount={}", cleanedCount);
        return cleanedCount;
    }

    /**
     * 获取任务快照
     * @param taskState 任务状态
     * @return 任务快照
     */
    private TaskInfo snapshot(TaskState taskState) {
        synchronized (taskState) {
            return taskState.snapshot();
        }
    }

    /**
     * 任务快照写入Redis
     * @param taskInfo 任务快照
     */
    private void persistTask(TaskInfo taskInfo) {
        if (redisTemplate == null || taskInfo == null) {
            return;
        }
        try {
            String key = TASK_REDIS_KEY_PREFIX + taskInfo.taskId();
            String json = objectMapper.writeValueAsString(taskInfo);
            redisTemplate.opsForValue().set(key, json, RUNNING_TASK_INACTIVE_TIMEOUT);
        } catch (Exception e) {
            log.debug("任务进度写入Redis失败: taskId={}, error={}",
                    taskInfo.taskId(), e.getMessage());
        }
    }

    /**
     * 从Redis中删除任务快照
     * @param taskId 任务编号
     */
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

    /** AsyncTaskService 唯一持有的可变任务状态。 */
    private static final class TaskState {
        private final String taskId;   // 任务编号
        private final String taskName;   // 任务名称
        private final String taskType;   // 任务类型
        private final LocalDateTime startTime;   // 开始时间
        private TaskStatus status;   // 任务状态
        private Integer overallPercent;   // 总体进度百分比
        private TaskResult result;   // 任务结果
        private TaskStage currentStage;   // 当前阶段
        private LocalDateTime endTime;   // 结束时间
        private LocalDateTime lastActivityTime;   // 最后活跃时间
        private long version;   // 版本号

        private TaskState(
                String taskId,
                String taskName,
                String taskType,
                TaskStatus status,
                Integer overallPercent,
                TaskResult result,
                TaskStage currentStage,
                LocalDateTime startTime,
                LocalDateTime endTime,
                long version) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.taskType = taskType;
            this.status = status;
            this.overallPercent = overallPercent;
            this.result = result;
            this.currentStage = currentStage;
            this.startTime = startTime;
            this.endTime = endTime;
            this.lastActivityTime = LocalDateTime.now();
            this.version = version;
        }

        /**
         * 创建任务状态
         * @param taskId 任务编号
         * @param taskName 任务名称
         * @param taskType 任务类型
         * @param total 总数
         * @param resultUnit 结果单位
         * @return 任务状态
         */
        private static TaskState create(
                String taskId,
                String taskName,
                String taskType,
                long total,
                TaskProgressUnit resultUnit) {
            return new TaskState(
                    taskId,
                    taskName,
                    taskType,
                    TaskStatus.RUNNING,
                    null,
                    new TaskResult(total, 0, 0, resultUnit),
                    TaskStage.indeterminate("queued", "任务已创建"),
                    LocalDateTime.now(),
                    null,
                    0);
        }

        /**
         * 从任务信息创建任务状态
         * @param info 任务信息
         * @return 任务状态
         */
        private static TaskState restore(TaskInfo info) {
            return new TaskState(
                    info.taskId(),
                    info.taskName(),
                    info.taskType(),
                    info.status(),
                    info.overallPercent(),
                    info.result(),
                    info.currentStage(),
                    info.startTime(),
                    info.endTime(),
                    info.version());
        }

        /**
         * 应用任务进度更新
         * @param updates 任务进度更新
         */
        private void apply(TaskProgressUpdate... updates) {
            TaskStage nextStage = currentStage;
            Integer nextOverallPercent = overallPercent;
            TaskResult nextResult = result;
            for (TaskProgressUpdate update : updates) {
                if (update instanceof TaskProgressUpdate.StageChanged stageChanged) {
                    nextStage = stageChanged.stage();
                } else if (update instanceof TaskProgressUpdate.OverallProgressChanged overallChanged) {
                    nextOverallPercent = nextOverallPercent == null
                            ? overallChanged.percent()
                            : Math.max(nextOverallPercent, overallChanged.percent());
                } else if (update instanceof TaskProgressUpdate.ResultChanged resultChanged) {
                    nextResult = nextResult.withCounts(
                            resultChanged.succeeded(), resultChanged.failed());
                } else if (update instanceof TaskProgressUpdate.ItemFinished itemFinished) {
                    nextResult = nextResult.finishItem(itemFinished.succeeded());
                }
            }
            currentStage = nextStage;
            overallPercent = nextOverallPercent;
            result = nextResult;
            lastActivityTime = LocalDateTime.now();
        }

        /**
         * 创建任务快照
         * @return 任务快照
         */
        private TaskInfo snapshot() {
            return new TaskInfo(
                    taskId,
                    taskName,
                    taskType,
                    status,
                    overallPercent,
                    result,
                    currentStage,
                    startTime,
                    endTime,
                    version);
        }
    }
}
