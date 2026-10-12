package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileOptRecord;
import org.ocean.admin.gis.entity.GisFileOptRecordItem;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileOptRecordMapper;
import org.ocean.admin.gis.mapper.GisFileOptRecordItemMapper;
import org.ocean.admin.gis.vo.GisFileOptRecordVO;
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
 * GIS 源数据文件操作记录
 *
 * @author DeepOcean
 * @since 2026-09-16
 */
@Service
@RequiredArgsConstructor
public class GisFileOptRecordService {
    private static final Set<String> RECORD_STATUSES = Set.of(
            "QUEUED", "RUNNING", "COMPLETED", "PARTIAL_FAILED", "FAILED");
    private static final Set<String> UPLOAD_STATUSES = Set.of("PENDING", "READY", "FAILED");

    public PageResult<List<GisFileOptRecordVO>> getImportRecordPage(
            Integer current,
            Integer size,
            Long dataSetId,
            Long categoryId,
            String recordNo,
            String recordStatus,
            String originalName,
            String extension,
            String uploadStatus,
            LocalDateTime createTimeStart,
            LocalDateTime createTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedRecordNo = trimToNull(recordNo);
        String normalizedRecordStatus = normalizeUpper(recordStatus);
        String normalizedOriginalName = trimToNull(originalName);
        String normalizedExtension = normalizeLower(extension);
        String normalizedUploadStatus = normalizeUpper(uploadStatus);
        validateQuery(normalizedRecordStatus, normalizedUploadStatus, createTimeStart, createTimeEnd);

        Page<GisFileOptRecordVO> page = recordMapper.selectImportRecordPage(
                new Page<>(currentPage, pageSize),
                dataSetId,
                categoryId,
                normalizedRecordNo,
                normalizedRecordStatus,
                normalizedOriginalName,
                normalizedExtension,
                normalizedUploadStatus,
                createTimeStart,
                createTimeEnd);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public GisFileOptRecordVO getImportRecordDetail(String recordNo) {
        String normalizedRecordNo = trimToNull(recordNo);
        if (normalizedRecordNo == null || normalizedRecordNo.length() > 64) {
            throw new IllegalArgumentException("导入记录编号不合法");
        }
        GisFileOptRecordVO record = recordMapper.selectImportRecordByNo(normalizedRecordNo);
        if (record == null) {
            throw new IllegalArgumentException("导入记录不存在: " + normalizedRecordNo);
        }
        List<GisFileOptRecordItem> recordItems = recordItemMapper.selectByRecordId(record.getId());
        record.setFiles(recordItems.stream()
                .map(item -> toFileSnapshotVO(item, record.getDataSetId(), record.getCategoryId()))
                .toList());
        return record;
    }

    private final GisFileOptRecordMapper recordMapper;
    private final GisFileMetaMapper fileMetaMapper;
    private final GisFileOptRecordItemMapper recordItemMapper;
    private final GisDataSetMapper dataSetMapper;

    public GisFileOptRecord findImportRecordByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return recordMapper.selectOne(new LambdaQueryWrapper<GisFileOptRecord>()
                .eq(GisFileOptRecord::getRequestId, requestId)
                .eq(GisFileOptRecord::getOperationType, "IMPORT"));
    }

    /** 首块到达时直接创建运行中的导入记录，不引入独立上传会话。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileOptRecord createRunningImportRecord(
            String recordNo,
            String requestId,
            Long dataSetId,
            int totalCount,
            long totalBytes,
            Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        GisFileOptRecord record = new GisFileOptRecord();
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

    public GisFileOptRecordItem getImportItem(Long recordId, int sequenceNo) {
        return recordItemMapper.selectOne(new LambdaQueryWrapper<GisFileOptRecordItem>()
                .eq(GisFileOptRecordItem::getRecordId, recordId)
                .eq(GisFileOptRecordItem::getSequenceNo, sequenceNo));
    }

    public List<GisFileOptRecordItem> getImportItems(Long recordId) {
        return recordItemMapper.selectByRecordId(recordId);
    }

    /** 首次接收某文件时建立运行中明细，后续块复用同一行。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileOptRecordItem createRunningImportItem(
            Long recordId,
            int sequenceNo,
            String originalName,
            String extension,
            long sizeBytes,
            Long operatorId) {
        GisFileOptRecordItem existing = getImportItem(recordId, sequenceNo);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        GisFileOptRecordItem item = new GisFileOptRecordItem();
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
        List<GisFileOptRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<GisFileOptRecord>()
                        .eq(GisFileOptRecord::getOperationType, "IMPORT")
                        .eq(GisFileOptRecord::getRecordStatus, "RUNNING"));
        for (GisFileOptRecord record : records) {
            long remaining = Math.max(
                    0L,
                    valueOrZero(record.getTotalBytes()) - valueOrZero(record.getUploadedBytes()));
            reserved = Math.addExact(reserved, remaining);
        }
        return reserved;
    }

    /** 每个请求块完成后仅写一次数据库进度；重复块不会导致累计翻倍。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileOptRecord updateUploadedBytes(Long recordId, long uploadedBytes) {
        GisFileOptRecord record = recordMapper.selectById(recordId);
        if (record == null || !"RUNNING".equals(record.getRecordStatus())) {
            throw new IllegalStateException("上传记录不存在或已结束: " + recordId);
        }
        if (uploadedBytes < 0 || uploadedBytes > valueOrZero(record.getTotalBytes())) {
            throw new IllegalArgumentException("上传进度字节数不合法");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = recordMapper.update(null, new UpdateWrapper<GisFileOptRecord>()
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
    public GisFileOptRecord completeImportFile(
            Long recordId,
            int sequenceNo,
            GisFileMeta fileMeta) {
        GisFileOptRecord record = requiredRunningRecord(recordId);
        GisFileOptRecordItem item = getImportItem(recordId, sequenceNo);
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
                new UpdateWrapper<GisFileOptRecordItem>()
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
        UpdateWrapper<GisFileOptRecord> recordUpdate = new UpdateWrapper<GisFileOptRecord>()
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
    public GisFileOptRecord failImportFile(
            Long recordId, int sequenceNo, String errorMessage) {
        GisFileOptRecord record = requiredRunningRecord(recordId);
        GisFileOptRecordItem item = getImportItem(recordId, sequenceNo);
        if (item == null || !"RUNNING".equals(item.getOperationStatus())) {
            throw new IllegalStateException("上传文件明细不处于运行状态: " + sequenceNo);
        }
        LocalDateTime now = LocalDateTime.now();
        if (recordItemMapper.update(null, new UpdateWrapper<GisFileOptRecordItem>()
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
        UpdateWrapper<GisFileOptRecord> update = new UpdateWrapper<GisFileOptRecord>()
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

    private GisFileOptRecord requiredRunningRecord(Long recordId) {
        GisFileOptRecord record = recordMapper.selectById(recordId);
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
    public GisFileOptRecord createDownloadRecord(GisFileMeta fileMeta, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        GisFileOptRecord record = new GisFileOptRecord();
        record.setRecordNo("GIS_EXPORT_" + IdWorker.getIdStr());
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

        GisFileOptRecordItem item = new GisFileOptRecordItem();
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
            String recordStatus,
            String uploadStatus,
            LocalDateTime createTimeStart,
            LocalDateTime createTimeEnd) {
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
            GisFileOptRecordItem item,
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
