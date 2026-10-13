package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.config.FileUploadConfig;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.GisUploadChunkCommand;
import org.ocean.admin.gis.dto.TaskInfo;
import org.ocean.admin.gis.dto.TaskProgressUnit;
import org.ocean.admin.gis.dto.TaskProgressUpdate;
import org.ocean.admin.gis.dto.TaskStage;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileOptRecord;
import org.ocean.admin.gis.entity.GisFileOptRecordItem;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多请求分块、块内流式的 GIS 文件上传业务编排服务。
 *
 * <p>本服务负责：</p>
 * <ul>
 *     <li>校验批次、文件、分块及数据集约束；</li>
 *     <li>以 requestId 保证批次幂等，并在首块创建 upload_ 前缀任务；</li>
 *     <li>约束批次内文件顺序，处理重复块、断点续传和故障恢复；</li>
 *     <li>协调流式落盘、文件转正、元数据入库和数据集文件计数；</li>
 *     <li>维护 Redis 实时任务快照并推送 WebSocket 上传进度。</li>
 *     <li>在批次终态清理空目录，并回收长时间无活动的暂存资源。</li>
 * </ul>
 *
 * <p>HTTP 请求头解析由 Controller 负责，具体文件 IO 由 {@link FileUploadUtil} 负责，
 * 数据库事务由 {@link GisFileOptRecordService} 负责。本服务不保存独立上传会话。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadService {

    private static final int MAX_FILES_PER_BATCH = 100;
    private static final int UPLOAD_PROGRESS_END = 99;
    private static final long PROGRESS_PUSH_INTERVAL_NANOS = 500_000_000L;
    private static final Object[] REQUEST_LOCKS = createLockStripes();
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "tif", "tiff", "img", "jp2", "png", "jpg", "jpeg", "tfw", "prj", "aux", "xml");
    private static final Set<String> TERRAIN_EXTENSIONS = Set.of(
            "tif", "tiff", "dem", "hgt", "terrain", "asc", "prj", "xml");
    private static final Set<String> VECTOR_EXTENSIONS = Set.of(
            "shp", "shx", "dbf", "prj", "cpg", "sbn", "sbx", "geojson", "json", "kml", "kmz", "gpkg");

    private final GisDataSetMapper gisDataSetMapper;
    private final FileUploadUtil fileUploadUtil;
    private final AsyncTaskService asyncTaskService;
    private final GisFileOptRecordService gisFileOptRecordService;
    private final FileUploadConfig uploadConfig;
    private final Object capacityMonitor = new Object();

    /**
     * 接收一个上传分块。首块创建服务端任务，后续请求通过 requestId 继续同一批次。
     *
     * <p>处理流程：</p>
     * <ol>
     *     <li>校验请求参数，并按 requestId 串行化同一批次的并发请求；</li>
     *     <li>首块创建导入记录，已有批次则校验不可变参数和操作人；</li>
     *     <li>识别批次或文件终态重试，消费请求体后幂等返回；</li>
     *     <li>恢复实时进度任务，并校验文件顺序及单文件明细；</li>
     *     <li>校验文件类型；不匹配时登记单文件失败并允许继续下一文件；</li>
     *     <li>恢复“文件已转正但数据库未提交”的异常窗口；</li>
     *     <li>块内流式写盘，实时推送进度，完整块结束后仅更新一次数据库字节进度；</li>
     *     <li>文件完整时原子转正并提交元数据；批次完成时收口实时任务。</li>
     * </ol>
     *
     * @param command 当前分块及所属批次、文件的不可变元数据
     * @param input 当前分块的原始二进制流
     * @return 服务端任务号、下一偏移量以及文件和批次状态
     */
    public Map<String, Object> uploadChunk(
            GisUploadChunkCommand command,
            InputStream input) {
        // 1. 同一 requestId 只允许一个请求推进，避免块偏移和批次计数发生竞争。
        validateChunk(command);
        synchronized (requestLock(command.requestId())) {
            // 2. 只有第一个文件的首块能够创建批次；之后所有字段必须与首块声明一致。
            GisFileOptRecord record = getOrCreateBatch(command);
            validateBatch(record, command);
            validateBatchOwner(record);

            // 3. 客户端可能因响应丢失而重发终态块，校验文件身份并幂等返回原结果。
            if (!"RUNNING".equals(record.getRecordStatus())) {
                GisFileOptRecordItem terminalItem = gisFileOptRecordService.getImportItem(
                        record.getId(), command.sequenceNo());
                if (terminalItem == null || !isItemFinished(terminalItem)) {
                    throw new IllegalStateException("已结束上传批次中不存在该文件: " + command.fileIndex());
                }
                validateFile(terminalItem, command);
                consumeChunkBody(input, command.chunkSize());
                return buildChunkResult(record, command, terminalItem, true, false);
            }

            // 4. Redis 任务可由数据库记录恢复；数据库始终是上传状态的权威来源。
            ensureProgressTask(record);
            List<GisFileOptRecordItem> items = gisFileOptRecordService.getImportItems(record.getId());
            int expectedFileIndex = record.getCompletedCount() + record.getFailedCount();
            if (command.fileIndex() > expectedFileIndex) {
                throw new IllegalStateException(
                        "文件必须按顺序上传，当前应上传fileIndex=" + expectedFileIndex);
            }

            GisFileOptRecordItem item = findItemBySequence(items, command.sequenceNo());
            if (command.fileIndex() < expectedFileIndex) {
                if (item == null || !isItemFinished(item)) {
                    throw new IllegalStateException("上传记录文件状态不一致: " + command.fileIndex());
                }
                validateFile(item, command);
                consumeChunkBody(input, command.chunkSize());
                return buildChunkResult(record, command, item, true, false);
            }

            // 5. 文件首次出现时建立明细；后续块必须保持文件名和声明大小不变。
            if (item == null) {
                if (command.offset() != 0) {
                    throw new IllegalStateException("新文件首块offset必须为0");
                }
                validateDeclaredSizes(items, command);
                item = gisFileOptRecordService.createRunningImportItem(
                        record.getId(),
                        command.sequenceNo(),
                        normalizeFileName(command.fileName()),
                        extensionOrNull(command.fileName()),
                        command.fileSize(),
                        getCurrentOperatorId());
            } else {
                validateFile(item, command);
                if (isItemFinished(item)) {
                    consumeChunkBody(input, command.chunkSize());
                    return buildChunkResult(record, command, item, true, false);
                }
            }

            // 6. 类型不匹配属于单文件业务失败，不阻断批次中后续文件的上传。
            GisDataSet dataSet = requireDataSet(record.getDataSetId());
            try {
                validateFileType(dataSet, command.fileName());
            } catch (IllegalArgumentException ex) {
                consumeChunkBody(input, command.chunkSize());
                fileUploadUtil.deleteUploadPartial(record.getRecordNo(), command.fileIndex());
                record = gisFileOptRecordService.failImportFile(
                        record.getId(), command.sequenceNo(), ex.getMessage());
                publishFileResult(record, command.fileName());
                finishTaskIfDone(record);
                return buildChunkResult(
                        record,
                        command,
                        gisFileOptRecordService.getImportItem(record.getId(), command.sequenceNo()),
                        false,
                        true);
            }

            // 7. 覆盖文件已转正、数据库事务尚未提交时进程退出的恢复场景。
            long completedBytes = successfulBytes(items);
            GisStoredFile recovered = fileUploadUtil.findCompletedFile(
                    record.getDataSetId(),
                    record.getRecordNo(),
                    command.fileIndex(),
                    command.fileName(),
                    command.fileSize());
            if (recovered != null) {
                consumeChunkBody(input, command.chunkSize());
                record = completeFile(record, command, recovered);
                return buildChunkResult(record, command,
                        gisFileOptRecordService.getImportItem(record.getId(), command.sequenceNo()),
                        true, false);
            }

            // 8. FileUploadUtil 校验重叠字节并追加新内容；块内进度只推送 Redis/WebSocket。
            long[] lastPushNanos = {0L};
            int[] lastProgress = {-1};
            GisFileOptRecord currentRecord = record;
            FileUploadUtil.ChunkWriteResult writeResult = fileUploadUtil.appendChunk(
                    record.getRecordNo(),
                    command.fileIndex(),
                    command.offset(),
                    command.fileSize(),
                    command.chunkSize(),
                    input,
                    fileOffset -> reportStreamProgress(
                            currentRecord,
                            command.fileName(),
                            completedBytes + fileOffset,
                            lastPushNanos,
                            lastProgress));

            // 9. 完整读取并落盘一个请求块后，数据库进度只提交一次，重复块不会重复累计。
            long uploadedBytes = Math.min(
                    record.getTotalBytes(), completedBytes + writeResult.nextOffset());
            record = gisFileOptRecordService.updateUploadedBytes(record.getId(), uploadedBytes);
            publishProgress(record, command.fileName(), uploadedBytes);

            // 10. 文件完成后先原子转入正式目录，再事务提交元数据；提交失败则恢复暂存态。
            if (writeResult.fileCompleted()) {
                GisStoredFile storedFile = fileUploadUtil.completeStagedFile(
                        record.getDataSetId(),
                        record.getRecordNo(),
                        command.fileIndex(),
                        command.fileName(),
                        command.fileSize(),
                        writeResult.sha256());
                try {
                    record = completeFile(record, command, storedFile);
                } catch (RuntimeException ex) {
                    try {
                        fileUploadUtil.restoreCompletedFile(
                                record.getDataSetId(),
                                record.getRecordNo(),
                                command.fileIndex(),
                                command.fileName());
                    } catch (RuntimeException restoreEx) {
                        ex.addSuppressed(restoreEx);
                    }
                    throw ex;
                }
            }

            return buildChunkResult(
                    record,
                    command,
                    gisFileOptRecordService.getImportItem(record.getId(), command.sequenceNo()),
                    writeResult.duplicate(),
                    false);
        }
    }

    /** 首块创建权威数据库记录，并在容量检查期间避免本实例内重复预留磁盘空间。 */
    private GisFileOptRecord getOrCreateBatch(GisUploadChunkCommand command) {
        GisFileOptRecord existing = gisFileOptRecordService.findImportRecordByRequestId(
                command.requestId());
        if (existing != null) {
            return existing;
        }
        if (command.fileIndex() != 0 || command.offset() != 0) {
            throw new IllegalStateException("上传批次不存在，必须从第一个文件首块开始");
        }
        requireDataSet(command.dataSetId());
        synchronized (capacityMonitor) {
            existing = gisFileOptRecordService.findImportRecordByRequestId(command.requestId());
            if (existing != null) {
                return existing;
            }
            long reserved = gisFileOptRecordService.calculateReservedUploadBytes();
            long required = Math.addExact(
                    Math.addExact(reserved, command.totalSize()),
                    uploadConfig.getMinFreeSpace().toBytes());
            fileUploadUtil.ensureFreeSpace(required);
            String taskId = GisTaskNoGenerator.generateUploadTaskNo();
            try {
                return gisFileOptRecordService.createRunningImportRecord(
                        taskId,
                        command.requestId(),
                        command.dataSetId(),
                        command.fileCount(),
                        command.totalSize(),
                        getCurrentOperatorId());
            } catch (DuplicateKeyException ex) {
                GisFileOptRecord raced = gisFileOptRecordService.findImportRecordByRequestId(
                        command.requestId());
                if (raced != null) {
                    return raced;
                }
                throw ex;
            }
        }
    }

    /** 提交单文件成功结果，并同步发布文件计数及批次终态。 */
    private GisFileOptRecord completeFile(
            GisFileOptRecord record,
            GisUploadChunkCommand command,
            GisStoredFile storedFile) {
        GisFileMeta meta = createFileMeta(record, command, storedFile);
        GisFileOptRecord updated = gisFileOptRecordService.completeImportFile(
                record.getId(), command.sequenceNo(), meta);
        publishFileResult(updated, command.fileName());
        finishTaskIfDone(updated);
        return updated;
    }

    private void validateChunk(GisUploadChunkCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("上传分块参数不能为空");
        }
        if (!command.requestId().matches("[A-Za-z0-9_-]{8,64}")) {
            throw new IllegalArgumentException("requestId格式不合法");
        }
        if (command.fileCount() > MAX_FILES_PER_BATCH) {
            throw new IllegalArgumentException("单批次文件数量不能超过" + MAX_FILES_PER_BATCH);
        }
        if (command.fileSize() > uploadConfig.getMaxFileSize().toBytes()) {
            throw new IllegalArgumentException(
                    "单文件不能超过" + formatByteSize(uploadConfig.getMaxFileSize().toBytes()));
        }
        if (command.totalSize() > uploadConfig.getMaxBatchSize().toBytes()) {
            throw new IllegalArgumentException("单批次文件总大小不能超过100GB");
        }
        if (command.chunkSize() > uploadConfig.getMaxChunkSize().toBytes()) {
            throw new IllegalArgumentException("单个上传分块不能超过128MiB");
        }
        normalizeFileName(command.fileName());
    }

    private void validateBatch(
            GisFileOptRecord record,
            GisUploadChunkCommand command) {
        if (!command.requestId().equals(record.getRequestId())
                || !command.dataSetId().equals(record.getDataSetId())
                || command.fileCount() != record.getTotalCount()
                || command.totalSize() != record.getTotalBytes()) {
            throw new IllegalArgumentException("requestId对应的上传批次参数不一致");
        }
    }

    private void validateDeclaredSizes(
            List<GisFileOptRecordItem> items,
            GisUploadChunkCommand command) {
        long declaredBefore = 0L;
        for (GisFileOptRecordItem existing : items) {
            declaredBefore = Math.addExact(declaredBefore, existing.getSizeBytes());
        }
        long declaredThroughCurrent = Math.addExact(declaredBefore, command.fileSize());
        if (command.lastFile() && declaredThroughCurrent != command.totalSize()) {
            throw new IllegalArgumentException("最后一个文件到达时，文件大小合计必须等于批次总大小");
        }
        if (!command.lastFile() && declaredThroughCurrent >= command.totalSize()) {
            throw new IllegalArgumentException("非最后文件不能耗尽批次声明总大小");
        }
    }

    private void validateFile(
            GisFileOptRecordItem item,
            GisUploadChunkCommand command) {
        if (!normalizeFileName(command.fileName()).equals(item.getOriginalName())
                || command.fileSize() != item.getSizeBytes()) {
            throw new IllegalArgumentException("同一fileIndex的文件名或文件大小不能变更");
        }
    }

    private void validateBatchOwner(GisFileOptRecord record) {
        if (record.getOperatorId() == null) {
            return;
        }
        CurrentOperator operator = getCurrentOperator();
        if (operator == null || !record.getOperatorId().equals(operator.userId())) {
            throw new IllegalStateException("当前用户无权提交该上传批次");
        }
    }

    /** Redis 快照丢失或服务重启时，根据数据库记录重建实时进度任务。 */
    private void ensureProgressTask(GisFileOptRecord record) {
        String taskId = record.getRecordNo();
        TaskInfo taskInfo = asyncTaskService.getTaskInfo(taskId);
        if (taskInfo == null) {
            asyncTaskService.registerTask(
                    taskId,
                    record.getRequestId(),
                    "GIS文件分块上传",
                    record.getTotalCount(),
                    "GIS_UPLOAD",
                    TaskProgressUnit.FILE);
            asyncTaskService.updateProgress(
                    taskId,
                    TaskProgressUpdate.resultChanged(
                            record.getCompletedCount(), record.getFailedCount()),
                    TaskProgressUpdate.stageChanged(createByteStage(
                            "uploading",
                            "等待上传数据",
                            record.getUploadedBytes(),
                            record.getTotalBytes())),
                    TaskProgressUpdate.overallProgressChanged(progressPercent(
                            record.getUploadedBytes(), record.getTotalBytes())));
            return;
        }
        if (!record.getRequestId().equals(taskInfo.correlationId())
                || !"GIS_UPLOAD".equals(taskInfo.taskType())
                || taskInfo.result().total() != record.getTotalCount()) {
            throw new IllegalStateException("实时进度任务与上传记录不一致: " + taskId);
        }
    }

    private void reportStreamProgress(
            GisFileOptRecord record,
            String fileName,
            long uploadedBytes,
            long[] lastPushNanos,
            int[] lastProgress) {
        int progress = progressPercent(uploadedBytes, record.getTotalBytes());
        long now = System.nanoTime();
        if (progress <= lastProgress[0]
                || (lastPushNanos[0] != 0L
                && now - lastPushNanos[0] < PROGRESS_PUSH_INTERVAL_NANOS)) {
            return;
        }
        lastPushNanos[0] = now;
        lastProgress[0] = progress;
        publishProgress(record, fileName, uploadedBytes);
    }

    private void publishProgress(
            GisFileOptRecord record, String fileName, long uploadedBytes) {
        asyncTaskService.updateProgress(
                record.getRecordNo(),
                TaskProgressUpdate.stageChanged(createByteStage(
                        "uploading",
                        "正在上传：" + fileName + "（"
                                + formatByteSize(uploadedBytes) + "/"
                                + formatByteSize(record.getTotalBytes()) + "）",
                        uploadedBytes,
                        record.getTotalBytes())),
                TaskProgressUpdate.overallProgressChanged(
                        progressPercent(uploadedBytes, record.getTotalBytes())));
    }

    private void publishFileResult(GisFileOptRecord record, String fileName) {
        asyncTaskService.updateProgress(
                record.getRecordNo(),
                TaskProgressUpdate.resultChanged(
                        record.getCompletedCount(), record.getFailedCount()),
                TaskProgressUpdate.stageChanged(TaskStage.indeterminate(
                        "file_finished", "文件处理完成：" + fileName)));
    }

    private void finishTaskIfDone(GisFileOptRecord record) {
        if (!"RUNNING".equals(record.getRecordStatus())) {
            fileUploadUtil.deleteEmptyBatchDirectory(record.getRecordNo());
            asyncTaskService.finalizeTaskResult(
                    record.getRecordNo(),
                    record.getFailedCount() == 0
                            ? "全部文件上传完成"
                            : "上传结束，成功" + record.getCompletedCount()
                                    + "个，失败" + record.getFailedCount() + "个");
        }
    }

    /**
     * 回收超过空闲时限的上传批次，并重试上次未完成的暂存目录清理。
     *
     * @return 本轮完成清理收口的批次数量
     */
    public int cleanupExpiredBatches() {
        if (uploadConfig.getIdleTimeout().isZero()
                || uploadConfig.getIdleTimeout().isNegative()) {
            throw new IllegalStateException("GIS上传空闲超时时间必须大于0");
        }
        int limit = uploadConfig.getCleanupBatchSize();
        if (limit < 1) {
            throw new IllegalStateException("GIS上传单轮清理批次数必须大于0");
        }

        LocalDateTime inactiveBefore = LocalDateTime.now().minus(uploadConfig.getIdleTimeout());
        List<GisFileOptRecord> pending = gisFileOptRecordService.findCleanupPendingBatches(limit);
        int cleanedCount = 0;
        for (GisFileOptRecord record : pending) {
            if (cleanupBatch(record, inactiveBefore)) {
                cleanedCount++;
            }
        }

        int remaining = limit - pending.size();
        if (remaining < 1) {
            return cleanedCount;
        }
        List<GisFileOptRecord> expired = gisFileOptRecordService.findExpiredUploadBatches(
                inactiveBefore, remaining);
        for (GisFileOptRecord record : expired) {
            if (cleanupBatch(record, inactiveBefore)) {
                cleanedCount++;
            }
        }
        return cleanedCount;
    }

    private boolean cleanupBatch(GisFileOptRecord candidate, LocalDateTime inactiveBefore) {
        String lockKey = candidate.getRequestId() == null
                ? candidate.getRecordNo()
                : candidate.getRequestId();
        synchronized (requestLock(lockKey)) {
            GisFileOptRecord record = candidate;
            if ("RUNNING".equals(record.getRecordStatus())) {
                record = gisFileOptRecordService.expireUploadBatch(
                        record.getId(), record.getVersion(), inactiveBefore);
                if (record == null) {
                    return false;
                }
            }

            boolean taskFinalized = finalizeExpiredProgressTask(record);
            try {
                fileUploadUtil.deleteBatchDirectory(record.getRecordNo());
            } catch (RuntimeException ex) {
                log.warn("清理过期上传暂存目录失败，将在下轮重试: taskNo={}",
                        record.getRecordNo(), ex);
                return false;
            }
            if (!taskFinalized) {
                return false;
            }
            return gisFileOptRecordService.completeUploadCleanup(record.getId());
        }
    }

    private boolean finalizeExpiredProgressTask(GisFileOptRecord record) {
        try {
            asyncTaskService.updateProgress(
                    record.getRecordNo(),
                    TaskProgressUpdate.resultChanged(
                            record.getCompletedCount(), record.getFailedCount()));
            asyncTaskService.finalizeTaskResult(
                    record.getRecordNo(), "上传长时间无活动，系统已自动终止");
            return true;
        } catch (RuntimeException ex) {
            log.warn("结束过期上传实时任务失败，将在下轮重试: taskNo={}",
                    record.getRecordNo(), ex);
            return false;
        }
    }

    private TaskStage createByteStage(
            String code, String message, long completedBytes, long totalBytes) {
        return TaskStage.determinate(
                code,
                message,
                Math.min(Math.max(0L, completedBytes), totalBytes),
                totalBytes,
                TaskProgressUnit.BYTE);
    }

    private int progressPercent(long uploadedBytes, long totalBytes) {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int) Math.min(
                UPLOAD_PROGRESS_END,
                uploadedBytes * UPLOAD_PROGRESS_END / totalBytes);
    }

    private GisFileMeta createFileMeta(
            GisFileOptRecord record,
            GisUploadChunkCommand command,
            GisStoredFile storedFile) {
        LocalDateTime now = LocalDateTime.now();
        GisFileMeta meta = new GisFileMeta();
        meta.setId(IdWorker.getId());
        meta.setDataSetId(record.getDataSetId());
        meta.setOriginalName(normalizeFileName(command.fileName()));
        meta.setStorageName(storedFile.getStorageName());
        meta.setStorageKey(storedFile.getStorageKey());
        meta.setStorageType(storedFile.getStorageType());
        meta.setExtension(FileUploadUtil.extensionOf(command.fileName()));
        meta.setSizeBytes(storedFile.getSizeBytes());
        meta.setSha256(storedFile.getSha256());
        meta.setUploadedBy(getCurrentOperatorId());
        meta.setUploadStatus("READY");
        meta.setCleanupStatus("NOT_REQUIRED");
        meta.setCreateTime(now);
        meta.setUpdateTime(now);
        meta.setDeleted(0);
        return meta;
    }

    private GisDataSet requireDataSet(Long dataSetId) {
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + dataSetId);
        }
        if (dataSet.getCategoryId() == null
                || dataSet.getCategoryId() < 0
                || dataSet.getCategoryId() > 2) {
            throw new IllegalArgumentException("未知GIS数据类别: " + dataSet.getCategoryId());
        }
        return dataSet;
    }

    private void validateFileType(GisDataSet dataSet, String fileName) {
        String extension = FileUploadUtil.extensionOf(fileName);
        Set<String> allowed = switch (dataSet.getCategoryId().intValue()) {
            case 0 -> IMAGE_EXTENSIONS;
            case 1 -> TERRAIN_EXTENSIONS;
            case 2 -> VECTOR_EXTENSIONS;
            default -> throw new IllegalArgumentException(
                    "未知GIS数据类别: " + dataSet.getCategoryId());
        };
        if (!allowed.contains(extension)) {
            throw new IllegalArgumentException("文件类型与数据集类别不匹配: " + fileName);
        }
    }

    private Map<String, Object> buildChunkResult(
            GisFileOptRecord record,
            GisUploadChunkCommand command,
            GisFileOptRecordItem item,
            boolean duplicate,
            boolean fileFailed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", record.getRequestId());
        result.put("taskId", record.getRecordNo());
        result.put("dataSetId", record.getDataSetId());
        result.put("fileIndex", command.fileIndex());
        result.put("nextOffset", nextOffset(record, command, item));
        result.put("duplicate", duplicate);
        result.put("fileCompleted", item != null && "SUCCESS".equals(item.getOperationStatus()));
        result.put("fileFailed", fileFailed
                || (item != null && "FAILED".equals(item.getOperationStatus())));
        result.put("taskCompleted", !"RUNNING".equals(record.getRecordStatus()));
        result.put("completedCount", record.getCompletedCount());
        result.put("failedCount", record.getFailedCount());
        result.put("status", record.getRecordStatus());
        return result;
    }

    private long nextOffset(
            GisFileOptRecord record,
            GisUploadChunkCommand command,
            GisFileOptRecordItem item) {
        if (item != null && "SUCCESS".equals(item.getOperationStatus())) {
            return command.fileSize();
        }
        if (item != null && "FAILED".equals(item.getOperationStatus())) {
            return 0L;
        }
        long successfulBytes = successfulBytes(
                gisFileOptRecordService.getImportItems(record.getId()));
        return Math.max(0L, record.getUploadedBytes() - successfulBytes);
    }

    private GisFileOptRecordItem findItemBySequence(
            List<GisFileOptRecordItem> items,
            int sequenceNo) {
        return items.stream()
                .filter(item -> item.getSequenceNo() == sequenceNo)
                .findFirst()
                .orElse(null);
    }

    private boolean isItemFinished(GisFileOptRecordItem item) {
        return "SUCCESS".equals(item.getOperationStatus())
                || "FAILED".equals(item.getOperationStatus());
    }

    private long successfulBytes(List<GisFileOptRecordItem> items) {
        long completed = 0L;
        for (GisFileOptRecordItem item : items) {
            if ("SUCCESS".equals(item.getOperationStatus())) {
                completed = Math.addExact(completed, item.getSizeBytes());
            }
        }
        return completed;
    }

    private String normalizeFileName(String fileName) {
        String safe = FileUploadUtil.safeOriginalName(fileName);
        if (safe.length() > 255) {
            throw new IllegalArgumentException("文件名不能超过255个字符");
        }
        return safe;
    }

    private String extensionOrNull(String fileName) {
        try {
            return FileUploadUtil.extensionOf(fileName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 幂等或业务拒绝场景也必须完整消费当前 HTTP 请求体，避免污染连接上的后续请求。
     */
    private void consumeChunkBody(InputStream input, long expectedBytes) {
        try {
            byte[] buffer = new byte[(int) Math.min(
                    uploadConfig.getStreamBufferSize().toBytes(), 1024L * 1024)];
            long readBytes = 0L;
            while (readBytes < expectedBytes) {
                int read = input.read(
                        buffer, 0, (int) Math.min(buffer.length, expectedBytes - readBytes));
                if (read < 0) {
                    throw new IllegalArgumentException("上传分块内容短于声明大小");
                }
                if (read > 0) {
                    readBytes += read;
                }
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException("上传分块内容长于声明大小");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取上传分块失败", ex);
        }
    }

    private CurrentOperator getCurrentOperator() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object value = request.getAttribute(CurrentOperator.REQUEST_ATTRIBUTE);
        return value instanceof CurrentOperator operator ? operator : null;
    }

    private Long getCurrentOperatorId() {
        CurrentOperator operator = getCurrentOperator();
        return operator == null ? null : operator.userId();
    }

    private String formatByteSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0d);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0d * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0d * 1024 * 1024));
    }

    private static Object requestLock(String requestId) {
        return REQUEST_LOCKS[(requestId.hashCode() & Integer.MAX_VALUE) % REQUEST_LOCKS.length];
    }

    private static Object[] createLockStripes() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }
}
