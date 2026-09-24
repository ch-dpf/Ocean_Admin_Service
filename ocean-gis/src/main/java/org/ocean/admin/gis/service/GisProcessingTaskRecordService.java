package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisProcessingTaskMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisProcessingTaskDetailVO;
import org.ocean.admin.gis.vo.GisProcessingTaskSummaryVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** GIS 处理任务的持久化查询及生命周期状态更新。 */
@Service
@RequiredArgsConstructor
public class GisProcessingTaskRecordService {

    private static final Set<String> TASK_STATUSES =
            Set.of("QUEUED", "RUNNING", "COMPLETED", "PARTIAL_FAILED", "FAILED");

    private final GisProcessingTaskMapper taskMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisTileSetMapper tileSetMapper;
    private final ObjectMapper objectMapper;

    public PageResult<List<GisProcessingTaskSummaryVO>> getTaskPage(
            Integer current, Integer size, String taskNo, String taskName,
            String processingType, String sourceType, String taskStatus, Long dataSetId,
            LocalDateTime createTimeStart, LocalDateTime createTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedTaskNo = trimToNull(taskNo);
        String normalizedTaskName = trimToNull(taskName);
        String normalizedProcessingType = normalizeProcessingType(processingType);
        String normalizedSourceType = normalizeSourceType(sourceType);
        String normalizedStatus = normalizeStatus(taskStatus);
        if (normalizedStatus != null && !TASK_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException(
                    "任务状态只能为 QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED 或 FAILED");
        }
        if (createTimeStart != null && createTimeEnd != null
                && createTimeStart.isAfter(createTimeEnd)) {
            throw new IllegalArgumentException("创建开始时间不能晚于创建结束时间");
        }

        LambdaQueryWrapper<GisProcessingTask> query =
                new LambdaQueryWrapper<GisProcessingTask>()
                        .like(normalizedTaskNo != null,
                                GisProcessingTask::getTaskNo, normalizedTaskNo)
                        .like(normalizedTaskName != null,
                                GisProcessingTask::getTaskName, normalizedTaskName)
                        .eq(normalizedProcessingType != null,
                                GisProcessingTask::getProcessingType, normalizedProcessingType)
                        .eq(normalizedSourceType != null,
                                GisProcessingTask::getSourceType, normalizedSourceType)
                        .eq(normalizedStatus != null,
                                GisProcessingTask::getTaskStatus, normalizedStatus)
                        .ge(createTimeStart != null,
                                GisProcessingTask::getCreateTime, createTimeStart)
                        .le(createTimeEnd != null,
                                GisProcessingTask::getCreateTime, createTimeEnd)
                        .orderByDesc(GisProcessingTask::getCreateTime);
        if (dataSetId != null) {
            query.apply("EXISTS (SELECT 1 FROM ocean_gis.gis_processing_input pi "
                    + "JOIN ocean_gis.gis_file_meta fm ON fm.id = pi.file_meta_id "
                    + "WHERE pi.task_id = gis_processing_task.id AND fm.data_set_id = {0})",
                    dataSetId);
        }
        Page<GisProcessingTask> page = taskMapper.selectPage(
                new Page<>(currentPage, pageSize), query);
        List<GisProcessingTaskSummaryVO> records = page.getRecords().stream()
                .map(this::toSummary).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    public GisProcessingTaskDetailVO getTaskDetail(Long taskId) {
        GisProcessingTask task = getRequired(taskId);
        List<GisProcessingInput> inputs = inputMapper.selectByTaskId(taskId);
        GisTileSet tileSet = tileSetMapper.selectByTaskId(taskId);
        if (tileSet == null) {
            throw new IllegalStateException("处理任务关联的瓦片集不存在: " + taskId);
        }
        return GisProcessingTaskDetailVO.builder()
                .task(toSummary(task))
                .parameterSchemaVersion(task.getParameterSchemaVersion())
                .requestFingerprint(task.getRequestFingerprint())
                .parameters(readParameters(task))
                .inputs(inputs.stream().map(this::toInputVO).toList())
                .tileSet(toTileSetVO(tileSet))
                .build();
    }

    public void insert(GisProcessingTask task) {
        if (taskMapper.insertTask(task) != 1) {
            throw new IllegalStateException("GIS处理任务创建失败");
        }
    }

    public GisProcessingTask getRequired(Long taskId) {
        GisProcessingTask task = taskMapper.selectTaskById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("GIS处理任务不存在或已删除: " + taskId);
        }
        return task;
    }

    public void markRunning(Long taskId) {
        int updated = taskMapper.update(null,
                new LambdaUpdateWrapper<GisProcessingTask>()
                        .eq(GisProcessingTask::getId, taskId)
                        .eq(GisProcessingTask::getTaskStatus, "QUEUED")
                        .set(GisProcessingTask::getTaskStatus, "RUNNING")
                        .set(GisProcessingTask::getCurrentStage, "PROCESSING")
                        .set(GisProcessingTask::getStartTime, LocalDateTime.now())
                        .set(GisProcessingTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("GIS处理任务无法进入运行状态: " + taskId);
        }
    }

    public void incrementCompleted(Long taskId) {
        increment(taskId, "completed_count = completed_count + 1", true);
    }

    public void incrementFailed(Long taskId) {
        increment(taskId, "failed_count = failed_count + 1", false);
    }

    private void increment(Long taskId, String countSql, boolean success) {
        int updated = taskMapper.update(null,
                new LambdaUpdateWrapper<GisProcessingTask>()
                        .eq(GisProcessingTask::getId, taskId)
                        .eq(GisProcessingTask::getTaskStatus, "RUNNING")
                        .setSql(countSql)
                        .set(GisProcessingTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException(
                    "GIS处理任务进度更新失败: " + taskId + ", success=" + success);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public GisProcessingTask finish(Long taskId) {
        GisProcessingTask task = getRequired(taskId);
        String status = task.getFailedCount() == 0 ? "COMPLETED"
                : task.getCompletedCount() == 0 ? "FAILED" : "PARTIAL_FAILED";
        int updated = taskMapper.update(null,
                new LambdaUpdateWrapper<GisProcessingTask>()
                        .eq(GisProcessingTask::getId, taskId)
                        .eq(GisProcessingTask::getTaskStatus, "RUNNING")
                        .set(GisProcessingTask::getTaskStatus, status)
                        .set(GisProcessingTask::getCurrentStage, "FINISHED")
                        .set(GisProcessingTask::getFinishTime, LocalDateTime.now())
                        .set(GisProcessingTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("GIS处理任务结束状态更新失败: " + taskId);
        }
        task.setTaskStatus(status);
        return task;
    }

    public void markFailed(Long taskId, String errorMessage) {
        taskMapper.update(null,
                new LambdaUpdateWrapper<GisProcessingTask>()
                        .eq(GisProcessingTask::getId, taskId)
                        .notIn(GisProcessingTask::getTaskStatus,
                                "COMPLETED", "PARTIAL_FAILED", "FAILED")
                        .setSql("failed_count = GREATEST(failed_count, "
                                + "total_count - completed_count)")
                        .set(GisProcessingTask::getTaskStatus, "FAILED")
                        .set(GisProcessingTask::getCurrentStage, "FAILED")
                        .set(GisProcessingTask::getErrorMessage,
                                abbreviate(errorMessage, 1000))
                        .set(GisProcessingTask::getFinishTime, LocalDateTime.now())
                        .set(GisProcessingTask::getUpdateTime, LocalDateTime.now()));
    }

    private GisProcessingTaskSummaryVO toSummary(GisProcessingTask task) {
        GisProcessingTaskSummaryVO result = new GisProcessingTaskSummaryVO();
        result.setId(task.getId());
        result.setTaskNo(task.getTaskNo());
        result.setTaskName(task.getTaskName());
        result.setProcessingType(task.getProcessingType());
        result.setSourceType(task.getSourceType());
        result.setPriority(task.getPriority());
        result.setTotalCount(task.getTotalCount());
        result.setCompletedCount(task.getCompletedCount());
        result.setFailedCount(task.getFailedCount());
        result.setTaskStatus(task.getTaskStatus());
        result.setCurrentStage(task.getCurrentStage());
        result.setErrorMessage(task.getErrorMessage());
        result.setStartTime(task.getStartTime());
        result.setFinishTime(task.getFinishTime());
        result.setCreateTime(task.getCreateTime());
        result.setUpdateTime(task.getUpdateTime());
        return result;
    }

    private GisProcessingParameters readParameters(GisProcessingTask task) {
        try {
            return objectMapper.readValue(
                    task.getParametersJson(), GisProcessingParameters.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("处理任务参数快照无法解析: " + task.getId(), ex);
        }
    }

    private GisProcessingTaskDetailVO.InputVO toInputVO(GisProcessingInput input) {
        return new GisProcessingTaskDetailVO.InputVO(
                input.getId(), input.getSequenceNo(), input.getInputKind(),
                input.getInputStatus(), input.getFileMetaId(), input.getWorkspaceCode(),
                input.getRelativePath(), input.getStorageKey(), input.getOriginalName(),
                input.getExtension(), input.getSizeBytes(), input.getSha256(),
                input.getErrorMessage(), input.getCreateTime(), input.getUpdateTime());
    }

    private GisProcessingTaskDetailVO.TileSetVO toTileSetVO(GisTileSet tileSet) {
        return new GisProcessingTaskDetailVO.TileSetVO(
                tileSet.getId(), tileSet.getTileType(), tileSet.getTileSetStatus(),
                tileSet.getOutputKey(), tileSet.getTargetCrs(), tileSet.getTileProfile(),
                tileSet.getOutputFormat(), tileSet.getMinZoom(), tileSet.getMaxZoom(),
                tileSet.getManifestKey(), tileSet.getErrorMessage(),
                tileSet.getCreateTime(), tileSet.getUpdateTime());
    }

    private String normalizeProcessingType(String value) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return GisProcessingType.valueOf(normalized).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "处理类型只能为 TERRAIN、IMAGERY 或 VECTOR", ex);
        }
    }

    private String normalizeSourceType(String value) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return GisInputSourceType.valueOf(normalized).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "输入来源只能为 UPLOAD、WORKSPACE 或 MANAGED_FILE", ex);
        }
    }

    private String normalizeStatus(String value) {
        return upperTrimToNull(value);
    }

    private String upperTrimToNull(String value) {
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

    private String abbreviate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value : value.substring(0, maxLength);
    }
}
