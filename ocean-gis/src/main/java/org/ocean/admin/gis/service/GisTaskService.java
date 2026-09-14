package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisTaskMapper;
import org.ocean.admin.gis.vo.GisTaskVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** GIS 持久化任务服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GisTaskService {

    private static final Set<String> TASK_STATUSES =
            Set.of("QUEUED", "RUNNING", "COMPLETED", "PARTIAL_FAILED", "FAILED");

    private final GisTaskMapper gisTaskMapper;

    public PageResult<List<GisTaskVO>> getTaskPage(
            Integer current,
            Integer size,
            String taskNo,
            String taskName,
            Long taskType,
            String taskStatus,
            Long dataSetId,
            Integer deleted,
            LocalDateTime createTimeStart,
            LocalDateTime createTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedTaskNo = trimToNull(taskNo);
        String normalizedTaskName = trimToNull(taskName);
        String normalizedStatus = normalizeStatus(taskStatus);

        if (taskType != null && (taskType < 1 || taskType > 4)) {
            throw new IllegalArgumentException("任务类型只能为1-上传、2-切片、3-发布、4-导出或下载");
        }
        if (normalizedStatus != null && !TASK_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException(
                    "任务状态只能为 QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED 或 FAILED");
        }
        if (createTimeStart != null && createTimeEnd != null
                && createTimeStart.isAfter(createTimeEnd)) {
            throw new IllegalArgumentException("创建开始时间不能晚于创建结束时间");
        }

        LambdaQueryWrapper<GisTask> query = new LambdaQueryWrapper<GisTask>()
                .like(normalizedTaskNo != null, GisTask::getTaskNo, normalizedTaskNo)
                .like(normalizedTaskName != null, GisTask::getTaskName, normalizedTaskName)
                .eq(taskType != null, GisTask::getTaskType, taskType)
                .eq(normalizedStatus != null, GisTask::getTaskStatus, normalizedStatus)
                .eq(dataSetId != null, GisTask::getDataSetId, dataSetId)
                .eq(deleted != null, GisTask::getDeleted, deleted)
                .ge(createTimeStart != null, GisTask::getCreateTime, createTimeStart)
                .le(createTimeEnd != null, GisTask::getCreateTime, createTimeEnd)
                .orderByDesc(GisTask::getCreateTime);

        Page<GisTask> page = gisTaskMapper.selectPage(new Page<>(currentPage, pageSize), query);
        List<GisTaskVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

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

    private GisTaskVO toVO(GisTask task) {
        GisTaskVO result = new GisTaskVO();
        result.setId(task.getId());
        result.setTaskNo(task.getTaskNo());
        result.setTaskName(task.getTaskName());
        result.setTaskType(task.getTaskType());
        result.setPriority(task.getPriority());
        result.setTotalCount(task.getTotalCount());
        result.setCompletedCount(task.getCompletedCount());
        result.setFailedCount(task.getFailedCount());
        result.setTaskStatus(task.getTaskStatus());
        result.setCurrentStage(task.getCurrentStage());
        result.setErrorMessage(task.getErrorMessage());
        result.setDataSetId(task.getDataSetId());
        result.setProcessingType(task.getProcessingType());
        result.setSourceFileMetaId(task.getSourceFileMetaId());
        result.setOutputKey(task.getOutputKey());
        result.setParentTaskId(task.getParentTaskId());
        result.setRootTaskId(task.getRootTaskId());
        result.setStartTime(task.getStartTime());
        result.setFinishTime(task.getFinishTime());
        result.setCreateTime(task.getCreateTime());
        result.setUpdateTime(task.getUpdateTime());
        return result;
    }

    private String normalizeStatus(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
