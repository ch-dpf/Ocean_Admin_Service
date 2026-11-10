package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.ImageryProcessingParameters;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.dto.TerrainProcessingParameters;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisTileSetArchive;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisProcessingTaskMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.mapper.GisTileSetArchiveMapper;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisProcessingTaskDetailVO;
import org.ocean.admin.gis.vo.GisProcessingTaskSummaryVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** GIS 处理任务的持久化查询及生命周期状态更新。 */
@Service
@RequiredArgsConstructor
public class ProcessingTaskRecordService {

    private final GisProcessingTaskMapper taskMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisTileSetArchiveMapper archiveMapper;
    private final ObjectMapper objectMapper;
    private final GisPublicationMapper publicationMapper;
    private final GisProcessingStorageService storageService;

    /** 静态矢量成果只允许读取已完成且仍有效的任务目录。 */
    public java.nio.file.Path vectorArtifact(Long taskId, String relative) {
        var task = getRequired(taskId);
        var tiles = tileSetMapper.selectByTaskId(taskId);
        if (task.getProcessingType() != GisProcessingType.VECTOR || task.getTaskStatus() != TaskStatus.COMPLETED
                || tiles == null || !"READY".equals(tiles.getTileSetStatus()))
            throw new IllegalArgumentException("静态矢量成果尚未完成或已失效");
        if (!relative.matches("(manifest|style)\\.json|[0-9]{1,2}/[0-9]+/[0-9]+\\.(mvt|png)"))
            throw new IllegalArgumentException("非法矢量成果路径");
        if ((relative.endsWith(".png") && !"PNG".equals(tiles.getOutputFormat()))
                || (relative.endsWith(".mvt") && !"MVT".equals(tiles.getOutputFormat())))
            throw new IllegalArgumentException("请求格式与任务成果不一致");
        var root = storageService.resolveTiles(tiles.getOutputKey());
        var path = root.resolve(relative).normalize();
        try {
            if (!path.toRealPath().startsWith(root.toRealPath()) || !java.nio.file.Files.isRegularFile(path))
                throw new IllegalArgumentException("矢量成果文件不存在");
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("矢量成果文件不存在", ex); }
        return path;
    }

    public JsonNode vectorManifest(Long taskId) throws java.io.IOException {
        var manifest = (ObjectNode) objectMapper.readTree(vectorArtifact(taskId, "manifest.json").toFile());
        String extension = "PNG".equals(manifest.path("format").asText()) ? "png" : "mvt";
        manifest.put("tiles", "/api/gis/task/" + taskId + "/vector/{z}/{x}/{y}." + extension);
        manifest.put("style", "/api/gis/task/" + taskId + "/vector/style.json");
        manifest.put("tileVersion", "static-" + taskId);
        return manifest;
    }

    /** 发布、归档和删除共用任务行锁，事务调用方持有锁直到完成操作。 */
    public GisProcessingTask getRequiredForUpdate(Long taskId) {
        if (taskMapper.lockActiveTask(taskId) == null) {
            throw new IllegalArgumentException("GIS处理任务不存在或已删除: " + taskId);
        }
        return getRequired(taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void renameTask(Long taskId, String taskName) {
        String name = trimToNull(taskName);
        if (name == null || name.length() > 200) {
            throw new IllegalArgumentException("任务名称去除首尾空白后必须为1到200个字符");
        }
        getRequiredForUpdate(taskId);
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<GisProcessingTask>()
                .eq(GisProcessingTask::getId, taskId)
                .set(GisProcessingTask::getTaskName, name)
                .set(GisProcessingTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("任务重命名失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long taskId) {
        GisProcessingTask task = getRequiredForUpdate(taskId);
        if (!task.getTaskStatus().isTerminal()) {
            throw new IllegalStateException("排队中或处理中的静态瓦片生成任务不能删除");
        }
        GisTileSet tileSet = tileSetMapper.selectByTaskId(taskId);
        if (tileSet == null) {
            throw new IllegalStateException("处理任务关联的瓦片集不存在");
        }
        if (publicationMapper.selectCount(new LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getSourceTaskId, taskId)
                .eq(GisPublication::getStatus, PublicationRecordService.PUBLISHED)) > 0) {
            throw new IllegalStateException("任务存在已发布服务，请先停用服务后再删除");
        }
        List<GisTileSetArchive> archives = archiveMapper.selectAllByTaskId(taskId);
        if (archives.stream().anyMatch(archive -> !archive.getArchiveStatus().isTerminal())) {
            throw new IllegalStateException("任务正在归档，请等待归档结束后再删除");
        }
        // 文件系统不参与数据库事务；清理失败保留记录并返回错误，允许再次清理。
        storageService.deleteTaskArtifacts(task, tileSet, archives);
        archiveMapper.deleteByTaskPermanently(taskId);
        publicationMapper.deleteByTaskPermanently(taskId);
        inputMapper.delete(new LambdaQueryWrapper<GisProcessingInput>()
                .eq(GisProcessingInput::getTaskId, taskId));
        if (tileSetMapper.deleteById(tileSet.getId()) != 1
                || taskMapper.deletePermanently(taskId) != 1) {
            throw new IllegalStateException("任务及瓦片集删除失败");
        }
    }

    public PageResult<List<GisProcessingTaskSummaryVO>> getTaskPage(
            Integer current, Integer size, String taskNo, String taskName,
            String processingType, String sourceType, String taskStatus, Long dataSetId,
            LocalDateTime createTimeStart, LocalDateTime createTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedTaskNo = trimToNull(taskNo);
        String normalizedTaskName = trimToNull(taskName);
        GisProcessingType normalizedProcessingType = normalizeProcessingType(processingType);
        GisInputSourceType normalizedSourceType = normalizeSourceType(sourceType);
        TaskStatus normalizedStatus = normalizeStatus(taskStatus);
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
                    + "WHERE pi.task_id = gis_processing_task.id "
                    + "AND pi.source_data_set_id = {0})",
                    dataSetId);
        }
        Page<GisProcessingTask> page = taskMapper.selectPage(
                new Page<>(currentPage, pageSize), query);
        Map<Long, String> formatsByTaskId = page.getRecords().isEmpty() ? Map.of()
                : tileSetMapper.selectList(new LambdaQueryWrapper<GisTileSet>()
                        .select(GisTileSet::getTaskId, GisTileSet::getOutputFormat)
                        .in(GisTileSet::getTaskId, page.getRecords().stream()
                                .map(GisProcessingTask::getId).toList()))
                        .stream().collect(Collectors.toMap(GisTileSet::getTaskId, GisTileSet::getOutputFormat));
        List<GisProcessingTaskSummaryVO> records = page.getRecords().stream()
                .map(task -> toSummary(task, formatsByTaskId.get(task.getId()))).toList();
        enrichArchiveStatus(records);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    public GisProcessingTaskDetailVO getTaskDetail(Long taskId) {
        GisProcessingTask task = getRequired(taskId);
        List<GisProcessingInput> inputs = inputMapper.selectByTaskId(taskId);
        GisTileSet tileSet = tileSetMapper.selectByTaskId(taskId);
        if (tileSet == null) {
            throw new IllegalStateException("处理任务关联的瓦片集不存在: " + taskId);
        }
        GisProcessingTaskSummaryVO taskSummary = toSummary(task, tileSet.getOutputFormat());
        enrichArchiveStatus(List.of(taskSummary));
        return GisProcessingTaskDetailVO.builder()
                .task(taskSummary)
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
                        .eq(GisProcessingTask::getTaskStatus, TaskStatus.QUEUED)
                        .set(GisProcessingTask::getTaskStatus, TaskStatus.RUNNING)
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
                        .eq(GisProcessingTask::getTaskStatus, TaskStatus.RUNNING)
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
        if (task.getCompletedCount() + task.getFailedCount() != task.getTotalCount()) {
            throw new IllegalStateException("GIS处理任务仍有未处理项，不能进入终态: " + taskId);
        }
        TaskStatus status = task.getFailedCount() == 0 ? TaskStatus.COMPLETED
                : task.getCompletedCount() == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL_FAILED;
        int updated = taskMapper.update(null,
                new LambdaUpdateWrapper<GisProcessingTask>()
                        .eq(GisProcessingTask::getId, taskId)
                        .eq(GisProcessingTask::getTaskStatus, TaskStatus.RUNNING)
                        .set(GisProcessingTask::getTaskStatus, status)
                        .set(GisProcessingTask::getCurrentStage, "FINISHED")
                        .set(GisProcessingTask::getFinishTime, LocalDateTime.now())
                        .set(GisProcessingTask::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("GIS处理任务结束状态更新失败: " + taskId);
        }
        inputMapper.detachTaskFileReferences(taskId);
        task.setTaskStatus(status);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long taskId, String errorMessage) {
        int updated = taskMapper.update(null,
                new LambdaUpdateWrapper<GisProcessingTask>()
                        .eq(GisProcessingTask::getId, taskId)
                        .notIn(GisProcessingTask::getTaskStatus,
                                TaskStatus.COMPLETED, TaskStatus.PARTIAL_FAILED, TaskStatus.FAILED)
                        .setSql("failed_count = GREATEST(failed_count, "
                                + "total_count - completed_count)")
                        .set(GisProcessingTask::getTaskStatus, TaskStatus.FAILED)
                        .set(GisProcessingTask::getCurrentStage, "FAILED")
                        .set(GisProcessingTask::getErrorMessage,
                                abbreviate(errorMessage, 1000))
                        .set(GisProcessingTask::getFinishTime, LocalDateTime.now())
                        .set(GisProcessingTask::getUpdateTime, LocalDateTime.now()));
        if (updated > 0) {
            inputMapper.detachTaskFileReferences(taskId);
        }
    }

    private GisProcessingTaskSummaryVO toSummary(GisProcessingTask task, String outputFormat) {
        GisProcessingTaskSummaryVO result = new GisProcessingTaskSummaryVO();
        result.setId(task.getId());
        result.setTaskNo(task.getTaskNo());
        result.setTaskName(task.getTaskName());
        result.setProcessingType(task.getProcessingType());
        result.setOutputFormat(outputFormat);
        result.setSourceType(task.getSourceType());
        result.setSourceTypeName(task.getSourceType().displayName());
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

    private void enrichArchiveStatus(List<GisProcessingTaskSummaryVO> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> taskIds = records.stream()
                .map(GisProcessingTaskSummaryVO::getId)
                .toList();
        Map<Long, GisTileSetArchive> archivesByTaskId = archiveMapper.selectList(
                        new LambdaQueryWrapper<GisTileSetArchive>()
                                .in(GisTileSetArchive::getProcessingTaskId, taskIds))
                .stream()
                .collect(Collectors.toMap(
                        GisTileSetArchive::getProcessingTaskId, Function.identity()));
        records.forEach(record -> {
            GisTileSetArchive archive = archivesByTaskId.get(record.getId());
            if (archive == null) {
                record.setArchiveProgress(0);
                record.setArchiveDownloadAvailable(false);
                return;
            }
            record.setArchiveNo(archive.getArchiveNo());
            record.setArchiveStatus(archive.getArchiveStatus());
            record.setArchiveProgress(archiveProgress(archive));
            record.setArchiveDownloadAvailable(
                    archive.getArchiveStatus() == TaskStatus.COMPLETED
                            && archive.getStorageKey() != null);
        });
    }

    private int archiveProgress(GisTileSetArchive archive) {
        if (archive.getArchiveStatus() == TaskStatus.COMPLETED) {
            return 100;
        }
        if (archive.getTotalBytes() != null && archive.getTotalBytes() > 0) {
            return (int) Math.min(100,
                    (double) archive.getProcessedBytes() * 100 / archive.getTotalBytes());
        }
        if (archive.getTotalFileCount() != null && archive.getTotalFileCount() > 0) {
            return (int) Math.min(100,
                    (double) archive.getProcessedFileCount() * 100
                            / archive.getTotalFileCount());
        }
        return 0;
    }

    public GisProcessingParameters readParameters(GisProcessingTask task) {
        try {
            GisProcessingType type = task.getProcessingType();
            if (task.getParameterSchemaVersion() == null
                    || task.getParameterSchemaVersion() < 2) {
                return readLegacyParameters(type, task.getParametersJson());
            }
            return switch (type) {
                case TERRAIN -> readTerrainParameters(task);
                case IMAGERY -> normalizeImageryParameters(task,
                        objectMapper.readValue(task.getParametersJson(),
                                ImageryProcessingParameters.class));
                case VECTOR -> objectMapper.readValue(task.getParametersJson(),
                        GisProcessingParameters.VectorProcessingParameters.class);
            };
        } catch (JacksonException ex) {
            throw new IllegalStateException("处理任务参数快照无法解析: " + task.getId(), ex);
        }
    }

    private TerrainProcessingParameters readTerrainParameters(
            GisProcessingTask task) throws JacksonException {
        JsonNode parameters = objectMapper.readTree(task.getParametersJson());
        if (parameters instanceof ObjectNode objectParameters) {
            ObjectNode normalized = objectParameters.deepCopy();
            if (task.getParameterSchemaVersion() != null
                    && task.getParameterSchemaVersion() < 5) {
                moveLegacyField(normalized, "minDepth", "minZoom");
                moveLegacyField(normalized, "maxDepth", "maxZoom");
            }
            if (task.getParameterSchemaVersion() == null
                    || task.getParameterSchemaVersion() < 6) {
                putTextIfMissing(normalized, "targetCrs", "EPSG:4326");
                putTextIfMissing(normalized, "tileProfile", "TMS");
                putTextIfMissing(normalized, "outputFormat", "QUANTIZED_MESH");
                if ("GEODETIC".equalsIgnoreCase(
                        normalized.path("tileProfile").asText())) {
                    normalized.put("tileProfile", "TMS");
                }
            }
            parameters = normalized;
        }
        return objectMapper.treeToValue(parameters, TerrainProcessingParameters.class);
    }

    private void putTextIfMissing(ObjectNode parameters, String name, String value) {
        if (!parameters.has(name) || parameters.path(name).asText().isBlank()) {
            parameters.put(name, value);
        }
    }

    private void moveLegacyField(ObjectNode parameters, String legacyName, String currentName) {
        if (!parameters.has(currentName) && parameters.has(legacyName)) {
            parameters.set(currentName, parameters.get(legacyName));
        }
        parameters.remove(legacyName);
    }

    private GisProcessingParameters readLegacyParameters(
            GisProcessingType type, String parametersJson) throws JacksonException {
        JsonNode json = objectMapper.readTree(parametersJson);
        return switch (type) {
            case TERRAIN -> TerrainProcessingParameters.defaults();
            case IMAGERY -> new ImageryProcessingParameters(
                    "EPSG:3857",
                    "XYZ",
                    textOrDefault(json, "outputFormat", "PNG"),
                    integerOrNull(json, "minZoom"),
                    integerOrNull(json, "maxZoom"),
                    textOrDefault(json, "resampling", null),
                    booleanOrNull(json, "transparent"));
            case VECTOR -> throw new IllegalArgumentException("该任务没有静态矢量数据与样式快照，请重新创建任务");
        };
    }

    private ImageryProcessingParameters normalizeImageryParameters(
            GisProcessingTask task, ImageryProcessingParameters parameters) {
        if (task.getParameterSchemaVersion() != null
                && task.getParameterSchemaVersion() >= 4) {
            return parameters;
        }
        return new ImageryProcessingParameters(
                "EPSG:3857",
                task.getParameterSchemaVersion() != null
                        && task.getParameterSchemaVersion() >= 3
                        ? parameters.tileProfile() : "XYZ",
                parameters.outputFormat(),
                parameters.minZoom(),
                parameters.maxZoom(),
                parameters.resampling(),
                parameters.transparent());
    }

    private String textOrDefault(JsonNode json, String field, String defaultValue) {
        JsonNode value = json.path(field);
        return value.isString() && !value.asText().isBlank() ? value.asText() : defaultValue;
    }

    private Integer integerOrNull(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private Boolean booleanOrNull(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    private GisProcessingTaskDetailVO.InputVO toInputVO(GisProcessingInput input) {
        return new GisProcessingTaskDetailVO.InputVO(
                input.getId(), input.getSequenceNo(), input.getInputKind(),
                input.getInputStatus(), input.getFileMetaId(), input.getSourceFileMetaId(),
                input.getSourceFileGroupId(), input.getSourceGroupRevision(),
                input.getSourceDataSetId(), input.getSourceDataSetName(),
                input.getSourceStorageType(), input.getSourceStorageKey(), input.getSourceLocatorType(),
                input.getSourceModifiedAt(), input.getWorkspaceCode(),
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

    private GisProcessingType normalizeProcessingType(String value) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return GisProcessingType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "处理类型只能为 TERRAIN、IMAGERY 或 VECTOR", ex);
        }
    }

    private GisInputSourceType normalizeSourceType(String value) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return GisInputSourceType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "输入来源只能为 UPLOAD、WORKSPACE 或 MANAGED_FILE", ex);
        }
    }

    private TaskStatus normalizeStatus(String value) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return TaskStatus.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "任务状态只能为 QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED 或 FAILED", ex);
        }
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
