package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileTransferRecord;
import org.ocean.admin.gis.entity.GisFileTransferRecordItem;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileTransferRecordMapper;
import org.ocean.admin.gis.mapper.GisFileTransferRecordItemMapper;
import org.ocean.admin.gis.vo.GisFileTransferRecordVO;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * GIS 原始文件上传、下载传输记录。
 *
 * @author DeepOcean
 * @since 2026-09-16
 */
@Service
@RequiredArgsConstructor
public class FileTransferRecordService {
    private static final String UPLOAD_CLEANUP_PENDING = "UPLOAD_CLEANUP_PENDING";
    private static final Set<String> RECORD_STATUSES = Set.of(
            "QUEUED", "RUNNING", "COMPLETED", "PARTIAL_FAILED", "FAILED");
    private static final Set<String> OPERATION_TYPES = Set.of("IMPORT", "EXPORT");
    private static final Set<String> UPLOAD_STATUSES = Set.of("PENDING", "READY", "FAILED");

    public PageResult<List<GisFileTransferRecordVO>> getRecordPage(
            Integer current,
            Integer size,
            Long dataSetId,
            Long categoryId,
            String recordNo,
            String operationType,
            String recordStatus,
            String originalName,
            String extension,
            String uploadStatus,
            LocalDateTime createTimeStart,
            LocalDateTime createTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedRecordNo = trimToNull(recordNo);
        String normalizedOperationType = normalizeUpper(operationType);
        String normalizedRecordStatus = normalizeUpper(recordStatus);
        String normalizedOriginalName = trimToNull(originalName);
        String normalizedExtension = normalizeLower(extension);
        String normalizedUploadStatus = normalizeUpper(uploadStatus);
        validateQuery(normalizedOperationType, normalizedRecordStatus,
                normalizedUploadStatus, createTimeStart, createTimeEnd);

        Page<GisFileTransferRecordVO> page = recordMapper.selectRecordPage(
                new Page<>(currentPage, pageSize),
                dataSetId,
                categoryId,
                normalizedRecordNo,
                normalizedOperationType,
                normalizedRecordStatus,
                normalizedOriginalName,
                normalizedExtension,
                normalizedUploadStatus,
                createTimeStart,
                createTimeEnd);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public GisFileTransferRecordVO getRecordDetail(String recordNo) {
        String normalizedRecordNo = trimToNull(recordNo);
        if (normalizedRecordNo == null || normalizedRecordNo.length() > 64) {
            throw new IllegalArgumentException("文件传输记录编号不合法");
        }
        GisFileTransferRecordVO record = recordMapper.selectRecordByNo(normalizedRecordNo);
        if (record == null) {
            throw new IllegalArgumentException("文件传输记录不存在: " + normalizedRecordNo);
        }
        List<GisFileTransferRecordItem> recordItems = recordItemMapper.selectByRecordId(record.getId());
        record.setFiles(recordItems.stream()
                .map(item -> toFileSnapshotVO(item, record.getDataSetId(), record.getCategoryId()))
                .toList());
        return record;
    }

    private final GisFileTransferRecordMapper recordMapper;
    private final GisFileMetaMapper fileMetaMapper;
    private final GisFileTransferRecordItemMapper recordItemMapper;
    private final GisDataSetMapper dataSetMapper;

    public GisFileTransferRecord findImportRecordByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return recordMapper.selectOne(new LambdaQueryWrapper<GisFileTransferRecord>()
                .eq(GisFileTransferRecord::getRequestId, requestId)
                .eq(GisFileTransferRecord::getOperationType, "IMPORT"));
    }

    /** 首块到达时直接创建运行中的导入记录，不引入独立上传会话。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileTransferRecord createRunningImportRecord(
            String recordNo,
            String requestId,
            Long dataSetId,
            int totalCount,
            long totalBytes,
            Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        GisFileTransferRecord record = new GisFileTransferRecord();
        record.setRecordNo(recordNo);
        record.setRequestId(requestId);
        record.setOperationType("IMPORT");
        record.setDataSetId(dataSetId);
        record.setTotalCount(totalCount);
        record.setCompletedCount(0);
        record.setFailedCount(0);
        record.setTotalBytes(totalBytes);
        record.setUploadedBytes(0L);
        record.setRecordStatus("RUNNING");
        record.setCurrentStage("UPLOADING");
        record.setOperatorId(operatorId);
        record.setVersion(0L);
        record.setStartTime(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        record.setDeleted(0);
        if (recordMapper.insert(record) != 1) {
            throw new IllegalStateException("上传导入记录创建失败");
        }
        return record;
    }

    public GisFileTransferRecordItem getImportItem(Long recordId, int sequenceNo) {
        return recordItemMapper.selectOne(new LambdaQueryWrapper<GisFileTransferRecordItem>()
                .eq(GisFileTransferRecordItem::getRecordId, recordId)
                .eq(GisFileTransferRecordItem::getSequenceNo, sequenceNo));
    }

    public List<GisFileTransferRecordItem> getImportItems(Long recordId) {
        return recordItemMapper.selectByRecordId(recordId);
    }

    /** 首次接收某文件时建立运行中明细，后续块复用同一行。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileTransferRecordItem createRunningImportItem(
            Long recordId,
            int sequenceNo,
            String originalName,
            String extension,
            long sizeBytes,
            Long operatorId) {
        GisFileTransferRecordItem existing = getImportItem(recordId, sequenceNo);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        GisFileTransferRecordItem item = new GisFileTransferRecordItem();
        item.setRecordId(recordId);
        item.setSequenceNo(sequenceNo);
        item.setOriginalName(abbreviate(originalName, 255));
        item.setExtension(extension);
        item.setOperationStatus("RUNNING");
        item.setSizeBytes(sizeBytes);
        item.setUploadedBy(operatorId);
        item.setUploadStatus("PENDING");
        item.setCleanupStatus("NOT_REQUIRED");
        item.setCreateTime(now);
        item.setUpdateTime(now);
        if (recordItemMapper.insert(item) != 1) {
            throw new IllegalStateException("上传文件明细创建失败: " + originalName);
        }
        return item;
    }

    /** 统计运行中上传批次尚未落盘的声明字节数，用于磁盘容量预留。 */
    public long calculateReservedUploadBytes() {
        long reserved = 0L;
        List<GisFileTransferRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<GisFileTransferRecord>()
                        .eq(GisFileTransferRecord::getOperationType, "IMPORT")
                        .eq(GisFileTransferRecord::getRecordStatus, "RUNNING"));
        for (GisFileTransferRecord record : records) {
            long remaining = Math.max(
                    0L,
                    valueOrZero(record.getTotalBytes()) - valueOrZero(record.getUploadedBytes()));
            reserved = Math.addExact(reserved, remaining);
        }
        return reserved;
    }

    /** 查询超过空闲时间仍未推进的运行中上传批次。 */
    public List<GisFileTransferRecord> findExpiredUploadBatches(
            LocalDateTime inactiveBefore,
            int limit) {
        if (inactiveBefore == null || limit < 1) {
            throw new IllegalArgumentException("上传超时查询参数不合法");
        }
        return recordMapper.selectList(new LambdaQueryWrapper<GisFileTransferRecord>()
                .eq(GisFileTransferRecord::getOperationType, "IMPORT")
                .eq(GisFileTransferRecord::getRecordStatus, "RUNNING")
                .le(GisFileTransferRecord::getUpdateTime, inactiveBefore)
                .orderByAsc(GisFileTransferRecord::getUpdateTime)
                .last("LIMIT " + limit));
    }

    /** 查询上次未能完成暂存目录删除的上传批次。 */
    public List<GisFileTransferRecord> findCleanupPendingBatches(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("上传清理批次数必须大于0");
        }
        return recordMapper.selectList(new LambdaQueryWrapper<GisFileTransferRecord>()
                .eq(GisFileTransferRecord::getOperationType, "IMPORT")
                .eq(GisFileTransferRecord::getCurrentStage, UPLOAD_CLEANUP_PENDING)
                .orderByAsc(GisFileTransferRecord::getUpdateTime)
                .last("LIMIT " + limit));
    }

    /**
     * 以版本号和最后活动时间原子抢占过期批次，并把未完成文件计入失败数量。
     *
     * @return 抢占后的终态记录；批次已恢复活动或被其他清理线程处理时返回 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileTransferRecord expireUploadBatch(
            Long recordId,
            Long expectedVersion,
            LocalDateTime inactiveBefore) {
        if (recordId == null || expectedVersion == null || inactiveBefore == null) {
            throw new IllegalArgumentException("过期上传批次参数不能为空");
        }
        GisFileTransferRecord record = recordMapper.selectById(recordId);
        if (record == null || !"RUNNING".equals(record.getRecordStatus())) {
            return null;
        }
        int completed = record.getCompletedCount();
        int failed = record.getTotalCount() - completed;
        String terminalStatus = completed == 0 ? "FAILED" : "PARTIAL_FAILED";
        String errorMessage = "上传超过空闲时限未继续，系统已自动终止";
        LocalDateTime now = LocalDateTime.now();
        int updated = recordMapper.update(null, new UpdateWrapper<GisFileTransferRecord>()
                .eq("id", recordId)
                .eq("operation_type", "IMPORT")
                .eq("record_status", "RUNNING")
                .eq("version", expectedVersion)
                .le("update_time", inactiveBefore)
                .set("failed_count", failed)
                .set("record_status", terminalStatus)
                .set("current_stage", UPLOAD_CLEANUP_PENDING)
                .set("error_message", errorMessage)
                .set("finish_time", now)
                .set("update_time", now)
                .setSql("version = version + 1"));
        if (updated != 1) {
            return null;
        }
        recordItemMapper.update(null, new UpdateWrapper<GisFileTransferRecordItem>()
                .eq("record_id", recordId)
                .eq("operation_status", "RUNNING")
                .set("operation_status", "FAILED")
                .set("upload_status", "FAILED")
                .set("cleanup_status", "PENDING")
                .set("error_message", errorMessage)
                .set("update_time", now));
        return recordMapper.selectById(recordId);
    }

    /** 暂存资源删除成功后结束清理阶段；重复调用不会改变已收口记录。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean completeUploadCleanup(Long recordId) {
        if (recordId == null) {
            throw new IllegalArgumentException("上传记录ID不能为空");
        }
        GisFileTransferRecord record = recordMapper.selectById(recordId);
        if (record == null || !UPLOAD_CLEANUP_PENDING.equals(record.getCurrentStage())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        recordItemMapper.update(null, new UpdateWrapper<GisFileTransferRecordItem>()
                .eq("record_id", recordId)
                .eq("cleanup_status", "PENDING")
                .set("cleanup_status", "COMPLETED")
                .set("update_time", now));
        int updated = recordMapper.update(null, new UpdateWrapper<GisFileTransferRecord>()
                .eq("id", recordId)
                .eq("current_stage", UPLOAD_CLEANUP_PENDING)
                .set("current_stage", record.getRecordStatus())
                .set("update_time", now)
                .setSql("version = version + 1"));
        return updated == 1;
    }

    /** 每个请求块完成后仅写一次数据库进度；重复块不会导致累计翻倍。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileTransferRecord updateUploadedBytes(Long recordId, long uploadedBytes) {
        GisFileTransferRecord record = recordMapper.selectById(recordId);
        if (record == null || !"RUNNING".equals(record.getRecordStatus())) {
            throw new IllegalStateException("上传记录不存在或已结束: " + recordId);
        }
        if (uploadedBytes < 0 || uploadedBytes > valueOrZero(record.getTotalBytes())) {
            throw new IllegalArgumentException("上传进度字节数不合法");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = recordMapper.update(null, new UpdateWrapper<GisFileTransferRecord>()
                .eq("id", recordId)
                .eq("record_status", "RUNNING")
                .setSql("uploaded_bytes = GREATEST(uploaded_bytes, " + uploadedBytes + ")")
                .set("current_stage", "UPLOADING")
                .set("update_time", now)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new IllegalStateException("上传进度更新失败: " + record.getRecordNo());
        }
        return recordMapper.selectById(recordId);
    }

    /** 文件完整落盘后原子提交元数据、明细、批次计数及数据集计数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileTransferRecord completeImportFile(
            Long recordId,
            int sequenceNo,
            GisFileMeta fileMeta) {
        GisFileTransferRecord record = requiredRunningRecord(recordId);
        GisFileTransferRecordItem item = getImportItem(recordId, sequenceNo);
        if (item == null || !"RUNNING".equals(item.getOperationStatus())) {
            throw new IllegalStateException("上传文件明细不处于运行状态: " + sequenceNo);
        }
        LocalDateTime now = LocalDateTime.now();
        if (fileMetaMapper.insert(fileMeta) != 1) {
            throw new IllegalStateException("文件元数据写入失败: " + fileMeta.getOriginalName());
        }
        item.setFileMetaId(fileMeta.getId());
        item.captureFileMetaSnapshot(fileMeta);
        item.setOperationStatus("SUCCESS");
        item.setUpdateTime(now);
        int itemUpdated = recordItemMapper.update(null,
                new UpdateWrapper<GisFileTransferRecordItem>()
                        .eq("id", item.getId())
                        .eq("operation_status", "RUNNING")
                        .set("file_meta_id", item.getFileMetaId())
                        .set("storage_name", item.getStorageName())
                        .set("storage_key", item.getStorageKey())
                        .set("storage_type", item.getStorageType())
                        .set("extension", item.getExtension())
                        .set("operation_status", "SUCCESS")
                        .set("error_message", null)
                        .set("size_bytes", item.getSizeBytes())
                        .set("sha256", item.getSha256())
                        .set("uploaded_by", item.getUploadedBy())
                        .set("upload_status", item.getUploadStatus())
                        .set("cleanup_status", item.getCleanupStatus())
                        .set("update_time", now));
        if (itemUpdated != 1) {
            throw new IllegalStateException("上传文件明细提交失败: " + sequenceNo);
        }

        int completed = record.getCompletedCount() + 1;
        int failed = record.getFailedCount();
        boolean terminal = completed + failed == record.getTotalCount();
        String status = terminal ? (failed == 0 ? "COMPLETED" : "PARTIAL_FAILED") : "RUNNING";
        UpdateWrapper<GisFileTransferRecord> recordUpdate = new UpdateWrapper<GisFileTransferRecord>()
                .eq("id", recordId)
                .eq("record_status", "RUNNING")
                .eq("version", record.getVersion())
                .set("completed_count", completed)
                .set("record_status", status)
                .set("current_stage", terminal ? status : "UPLOADING")
                .set("update_time", now)
                .setSql("version = version + 1");
        if (terminal) {
            recordUpdate.set("finish_time", now);
        }
        if (recordMapper.update(null, recordUpdate) != 1) {
            throw new IllegalStateException("上传批次计数提交冲突: " + record.getRecordNo());
        }
        int dataSetUpdated = dataSetMapper.update(null, new UpdateWrapper<GisDataSet>()
                .eq("id", record.getDataSetId())
                .setSql("file_count = file_count + 1")
                .set("update_time", now));
        if (dataSetUpdated != 1) {
            throw new IllegalStateException("数据集文件数更新失败: " + record.getDataSetId());
        }
        return recordMapper.selectById(recordId);
    }

    /** 首块校验失败时登记单文件失败，并允许批次继续上传下一文件。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileTransferRecord failImportFile(
            Long recordId, int sequenceNo, String errorMessage) {
        GisFileTransferRecord record = requiredRunningRecord(recordId);
        GisFileTransferRecordItem item = getImportItem(recordId, sequenceNo);
        if (item == null || !"RUNNING".equals(item.getOperationStatus())) {
            throw new IllegalStateException("上传文件明细不处于运行状态: " + sequenceNo);
        }
        LocalDateTime now = LocalDateTime.now();
        if (recordItemMapper.update(null, new UpdateWrapper<GisFileTransferRecordItem>()
                .eq("id", item.getId())
                .eq("operation_status", "RUNNING")
                .set("operation_status", "FAILED")
                .set("upload_status", "FAILED")
                .set("error_message", abbreviate(errorMessage, 1000))
                .set("update_time", now)) != 1) {
            throw new IllegalStateException("上传文件失败状态提交冲突: " + sequenceNo);
        }
        int completed = record.getCompletedCount();
        int failed = record.getFailedCount() + 1;
        boolean terminal = completed + failed == record.getTotalCount();
        String status = terminal
                ? (completed == 0 ? "FAILED" : "PARTIAL_FAILED")
                : "RUNNING";
        UpdateWrapper<GisFileTransferRecord> update = new UpdateWrapper<GisFileTransferRecord>()
                .eq("id", recordId)
                .eq("record_status", "RUNNING")
                .eq("version", record.getVersion())
                .set("failed_count", failed)
                .set("record_status", status)
                .set("current_stage", terminal ? status : "UPLOADING")
                .set("error_message", abbreviate(errorMessage, 1000))
                .set("update_time", now)
                .setSql("version = version + 1");
        if (terminal) {
            update.set("finish_time", now);
        }
        if (recordMapper.update(null, update) != 1) {
            throw new IllegalStateException("上传批次失败计数提交冲突: " + record.getRecordNo());
        }
        return recordMapper.selectById(recordId);
    }

    private GisFileTransferRecord requiredRunningRecord(Long recordId) {
        GisFileTransferRecord record = recordMapper.selectById(recordId);
        if (record == null || !"RUNNING".equals(record.getRecordStatus())) {
            throw new IllegalStateException("上传记录不存在或已结束: " + recordId);
        }
        return record;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 登记单文件下载记录。
     *
     * <p>下载与导出共用导入导出记录表，该表的 operation_type 只区分 IMPORT 与 EXPORT，
     * 因此单次下载按“一个批次 + 一条成功明细”收口，便于与导入记录一起检索。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public GisFileTransferRecord createDownloadRecord(GisFileMeta fileMeta, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        GisFileTransferRecord record = new GisFileTransferRecord();
        record.setRecordNo(TaskNoGenerator.generateDownloadTaskNo());
        record.setOperationType("EXPORT");
        record.setDataSetId(fileMeta.getDataSetId());
        record.setTotalCount(1);
        record.setCompletedCount(1);
        record.setFailedCount(0);
        record.setRecordStatus("COMPLETED");
        record.setCurrentStage("COMPLETED");
        record.setOperatorId(operatorId);
        record.setVersion(0L);
        record.setStartTime(now);
        record.setFinishTime(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        record.setDeleted(0);
        if (recordMapper.insert(record) != 1) {
            throw new IllegalStateException("下载记录创建失败: " + fileMeta.getId());
        }

        GisFileTransferRecordItem item = new GisFileTransferRecordItem();
        item.setRecordId(record.getId());
        item.setFileMetaId(fileMeta.getId());
        item.setSequenceNo(1);
        item.captureFileMetaSnapshot(fileMeta);
        item.setOriginalName(abbreviate(fileMeta.getOriginalName(), 255));
        item.setOperationStatus("SUCCESS");
        item.setCreateTime(now);
        item.setUpdateTime(now);
        if (recordItemMapper.insert(item) != 1) {
            throw new IllegalStateException("下载记录明细创建失败: " + fileMeta.getId());
        }
        return record;
    }

    private void validateQuery(
            String operationType,
            String recordStatus,
            String uploadStatus,
            LocalDateTime createTimeStart,
            LocalDateTime createTimeEnd) {
        if (operationType != null && !OPERATION_TYPES.contains(operationType)) {
            throw new IllegalArgumentException("传输类型只能为 IMPORT 或 EXPORT");
        }
        if (recordStatus != null && !RECORD_STATUSES.contains(recordStatus)) {
            throw new IllegalArgumentException(
                    "记录状态只能为 QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED 或 FAILED");
        }
        if (uploadStatus != null && !UPLOAD_STATUSES.contains(uploadStatus)) {
            throw new IllegalArgumentException("文件状态只能为 PENDING、READY 或 FAILED");
        }
        if (createTimeStart != null && createTimeEnd != null
                && createTimeStart.isAfter(createTimeEnd)) {
            throw new IllegalArgumentException("创建开始时间不能晚于创建结束时间");
        }
    }

    private GisFileMetaVO toFileSnapshotVO(
            GisFileTransferRecordItem item,
            Long dataSetId,
            Long categoryId) {
        GisFileMetaVO vo = new GisFileMetaVO();
        vo.setId(item.getFileMetaId());
        vo.setDataSetId(dataSetId);
        vo.setCategoryId(categoryId);
        vo.setOriginalName(item.getOriginalName());
        vo.setStorageName(item.getStorageName());
        vo.setStorageKey(item.getStorageKey());
        vo.setStorageType(item.getStorageType());
        vo.setExtension(item.getExtension());
        vo.setSizeBytes(item.getSizeBytes());
        vo.setSha256(item.getSha256());
        vo.setUploadedBy(item.getUploadedBy());
        vo.setUploadStatus(item.getUploadStatus());
        vo.setCleanupStatus(item.getCleanupStatus());
        vo.setErrorMessage(item.getErrorMessage());
        vo.setCreateTime(item.getCreateTime());
        vo.setUpdateTime(item.getUpdateTime());
        return vo;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeUpper(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeLower(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

}
