package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileOptRecord;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileOptRecordMapper;
import org.ocean.admin.gis.vo.GisFileOptRecordVO;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
        String normalizedRecordNo = normalizeUpper(recordNo);
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
        String normalizedRecordNo = normalizeUpper(recordNo);
        if (normalizedRecordNo == null || normalizedRecordNo.length() > 64) {
            throw new IllegalArgumentException("导入记录编号不合法");
        }
        GisFileOptRecordVO record = recordMapper.selectImportRecordByNo(normalizedRecordNo);
        if (record == null) {
            throw new IllegalArgumentException("导入记录不存在: " + normalizedRecordNo);
        }
        List<GisFileMeta> fileMetas = fileMetaMapper.selectList(
                new LambdaQueryWrapper<GisFileMeta>()
                        .eq(GisFileMeta::getImportExportRecordId, record.getId())
                        .orderByAsc(GisFileMeta::getCreateTime)
                        .orderByAsc(GisFileMeta::getId));
        record.setFiles(fileMetas.stream()
                .map(meta -> toFileMetaVO(meta, record.getCategoryId()))
                .toList());
        return record;
    }

    private static final DateTimeFormatter RECORD_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final GisFileOptRecordMapper recordMapper;
    private final GisFileMetaMapper fileMetaMapper;
    private final GisDataSetMapper dataSetMapper;

    /** 先独立提交导入记录，确保后续批量文件元数据能够引用它。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileOptRecord createImportRecord(Long dataSetId, int totalCount) {
        LocalDateTime now = LocalDateTime.now();
        GisFileOptRecord record = new GisFileOptRecord();
        record.setRecordNo(generateRecordNo(now));
        record.setOperationType("IMPORT");
        record.setDataSetId(dataSetId);
        record.setTotalCount(totalCount);
        record.setCompletedCount(0);
        record.setFailedCount(0);
        record.setRecordStatus("QUEUED");
        record.setCurrentStage("VALIDATING");
        record.setVersion(0L);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        record.setDeleted(0);
        if (recordMapper.insert(record) != 1) {
            throw new IllegalStateException("导入记录创建失败");
        }
        return record;
    }

    /** 批量插入全部文件元数据，并原子更新准备阶段的汇总状态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void savePreparedFiles(
            GisFileOptRecord record,
            List<GisFileMeta> fileMetas,
            int pendingCount,
            int failedCount) {
        if (fileMetas == null || fileMetas.isEmpty()) {
            throw new IllegalArgumentException("待保存的文件元数据不能为空");
        }
        int inserted = fileMetaMapper.insertBatch(fileMetas);
        if (inserted != fileMetas.size()) {
            throw new IllegalStateException(
                    "文件元数据批量写入不完整: expected=" + fileMetas.size() + ", actual=" + inserted);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean allFailed = pendingCount == 0;
        UpdateWrapper<GisFileOptRecord> update = new UpdateWrapper<GisFileOptRecord>()
                .eq("id", record.getId())
                .eq("record_status", "QUEUED")
                .eq("current_stage", "VALIDATING")
                .set("failed_count", failedCount)
                .set("current_stage", allFailed ? "FAILED" : "STAGED")
                .set("record_status", allFailed ? "FAILED" : "QUEUED")
                .set("update_time", now)
                .set(allFailed, "start_time", now)
                .set(allFailed, "finish_time", now)
                .setSql("version = version + 1");
        if (recordMapper.update(null, update) != 1) {
            throw new IllegalStateException("导入记录准备状态更新失败: " + record.getRecordNo());
        }

        record.setFailedCount(failedCount);
        record.setCurrentStage(allFailed ? "FAILED" : "STAGED");
        record.setRecordStatus(allFailed ? "FAILED" : "QUEUED");
        record.setVersion(record.getVersion() + 1);
        record.setUpdateTime(now);
        if (allFailed) {
            record.setStartTime(now);
            record.setFinishTime(now);
        }
    }

    /** 准备阶段发生数据库级故障时，尽力把导入记录收口为失败。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markPreparationFailed(GisFileOptRecord record, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        recordMapper.update(null, new UpdateWrapper<GisFileOptRecord>()
                .eq("id", record.getId())
                .eq("record_status", "QUEUED")
                .eq("current_stage", "VALIDATING")
                .set("failed_count", record.getTotalCount())
                .set("record_status", "FAILED")
                .set("current_stage", "FAILED")
                .set("error_message", abbreviate(errorMessage, 1000))
                .set("start_time", now)
                .set("finish_time", now)
                .set("update_time", now)
                .setSql("version = version + 1"));
    }

    /** 将已建档的导入记录推进到异步转存阶段。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markImportRunning(Long recordId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = recordMapper.update(null, new UpdateWrapper<GisFileOptRecord>()
                .eq("id", recordId)
                .eq("record_status", "QUEUED")
                .set("record_status", "RUNNING")
                .set("current_stage", "TRANSFERRING")
                .set("start_time", now)
                .set("update_time", now)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new IllegalStateException("导入记录无法进入转存阶段: " + recordId);
        }
    }

    /** 单文件转存成功：同时更新元数据、导入计数和数据集文件数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markImportFileReady(
            Long recordId,
            Long dataSetId,
            Long fileMetaId,
            GisStoredFile storedFile) {
        LocalDateTime now = LocalDateTime.now();
        int metaUpdated = fileMetaMapper.update(null, new UpdateWrapper<GisFileMeta>()
                .eq("id", fileMetaId)
                .eq("import_export_record_id", recordId)
                .eq("upload_status", "PENDING")
                .set("storage_name", storedFile.getStorageName())
                .set("storage_key", storedFile.getStorageKey())
                .set("storage_type", storedFile.getStorageType())
                .set("size_bytes", storedFile.getSizeBytes())
                .set("sha256", storedFile.getSha256())
                .set("upload_status", "READY")
                .set("cleanup_status", "NOT_REQUIRED")
                .set("error_message", null)
                .set("update_time", now));
        if (metaUpdated != 1) {
            throw new IllegalStateException("文件元数据转正失败: " + fileMetaId);
        }
        incrementRecordCount(recordId, "completed_count", now);
        int dataSetUpdated = dataSetMapper.update(null, new UpdateWrapper<GisDataSet>()
                .eq("id", dataSetId)
                .setSql("file_count = file_count + 1")
                .set("update_time", now));
        if (dataSetUpdated != 1) {
            throw new IllegalStateException("数据集文件数更新失败: " + dataSetId);
        }
    }

    /** 单文件转存失败：保留失败信息并推进导入失败计数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markImportFileFailed(
            Long recordId,
            Long fileMetaId,
            String errorMessage,
            boolean cleaned,
            GisStoredFile storedFile) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<GisFileMeta> metaUpdate = new UpdateWrapper<GisFileMeta>()
                .eq("id", fileMetaId)
                .eq("import_export_record_id", recordId)
                .eq("upload_status", "PENDING")
                .set("upload_status", "FAILED")
                .set("cleanup_status", cleaned ? "COMPLETED" : "FAILED")
                .set("error_message", abbreviate(errorMessage, 1000))
                .set("update_time", now);
        if (cleaned) {
            metaUpdate.set("storage_name", null).set("storage_key", null);
        } else if (storedFile != null) {
            metaUpdate.set("storage_name", storedFile.getStorageName())
                    .set("storage_key", storedFile.getStorageKey())
                    .set("storage_type", storedFile.getStorageType());
        }
        if (fileMetaMapper.update(null, metaUpdate) != 1) {
            throw new IllegalStateException("文件元数据失败状态更新失败: " + fileMetaId);
        }
        incrementRecordCount(recordId, "failed_count", now);
    }

    /** 按最终计数将导入记录收口为成功、部分失败或失败。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public GisFileOptRecord finishImport(Long recordId) {
        GisFileOptRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new IllegalStateException("导入记录不存在: " + recordId);
        }
        int completed = record.getCompletedCount();
        int failed = record.getFailedCount();
        if (completed + failed != record.getTotalCount()) {
            throw new IllegalStateException("导入记录尚有文件未处理: " + record.getRecordNo());
        }
        String status = failed == 0 ? "COMPLETED" : completed == 0 ? "FAILED" : "PARTIAL_FAILED";
        LocalDateTime now = LocalDateTime.now();
        int updated = recordMapper.update(null, new UpdateWrapper<GisFileOptRecord>()
                .eq("id", recordId)
                .in("record_status", List.of("QUEUED", "RUNNING"))
                .set("record_status", status)
                .set("current_stage", status)
                .setSql("start_time = COALESCE(start_time, CURRENT_TIMESTAMP)")
                .set("finish_time", now)
                .set("update_time", now)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw new IllegalStateException("导入记录结束状态更新失败: " + record.getRecordNo());
        }
        record.setRecordStatus(status);
        record.setCurrentStage(status);
        record.setFinishTime(now);
        return record;
    }

    private void incrementRecordCount(Long recordId, String countColumn, LocalDateTime now) {
        int updated = recordMapper.update(null, new UpdateWrapper<GisFileOptRecord>()
                .eq("id", recordId)
                .in("record_status", List.of("QUEUED", "RUNNING"))
                .apply("completed_count + failed_count < total_count")
                .set("update_time", now)
                .setSql(countColumn + " = " + countColumn + " + 1, version = version + 1"));
        if (updated != 1) {
            throw new IllegalStateException("导入记录文件计数更新失败: " + recordId);
        }
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

    private GisFileMetaVO toFileMetaVO(GisFileMeta meta, Long categoryId) {
        GisFileMetaVO vo = new GisFileMetaVO();
        vo.setId(meta.getId());
        vo.setDataSetId(meta.getDataSetId());
        vo.setCategoryId(categoryId);
        vo.setTaskId(meta.getTaskId());
        vo.setImportExportRecordId(meta.getImportExportRecordId());
        vo.setOriginalName(meta.getOriginalName());
        vo.setStorageName(meta.getStorageName());
        vo.setStorageKey(meta.getStorageKey());
        vo.setStorageType(meta.getStorageType());
        vo.setExtension(meta.getExtension());
        vo.setSizeBytes(meta.getSizeBytes());
        vo.setSha256(meta.getSha256());
        vo.setUploadedBy(meta.getUploadedBy());
        vo.setUploadStatus(meta.getUploadStatus());
        vo.setCleanupStatus(meta.getCleanupStatus());
        vo.setErrorMessage(meta.getErrorMessage());
        vo.setCreateTime(meta.getCreateTime());
        vo.setUpdateTime(meta.getUpdateTime());
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

    private String generateRecordNo(LocalDateTime now) {
        return "GIS_IMPORT_" + now.format(RECORD_TIME) + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
