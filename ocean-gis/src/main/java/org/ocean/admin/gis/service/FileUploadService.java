package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileOptRecord;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisFileOptRecordVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 文件上传服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadService {

    private static final int MAX_FILES_PER_REQUEST = 100;
    private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024;
    private static final int STORAGE_PROGRESS_END = 99;
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

    public Map<String, Object> importTaskRegistry(Long dataSetId, Integer totalCount) {
        if (dataSetId == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (totalCount == null || totalCount < 1 || totalCount > MAX_FILES_PER_REQUEST) {
            throw new IllegalArgumentException(
                    "预计导入文件数量必须在1到" + MAX_FILES_PER_REQUEST + "之间");
        }
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + dataSetId);
        }
        if (dataSet.getCategoryId() == null
                || dataSet.getCategoryId() < 0
                || dataSet.getCategoryId() > 2) {
            throw new IllegalArgumentException("未知GIS数据类别: " + dataSet.getCategoryId());
        }

        GisFileOptRecord record = gisFileOptRecordService.createImportRecord(
                dataSetId, totalCount);
        try {
            asyncTaskService.registerTask(
                    record.getRecordNo(), "Gis文件批量导入", totalCount, "GIS_IMPORT");
        } catch (RuntimeException ex) {
            gisFileOptRecordService.markPreparationFailed(record, ex.getMessage());
            throw ex;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", record.getId());
        data.put("recordNo", record.getRecordNo());
        data.put("taskId", record.getRecordNo());
        data.put("dataSetId", dataSetId);
        data.put("totalCount", totalCount);
        data.put("status", record.getRecordStatus());
        data.put("stage", record.getCurrentStage());
        return data;
    }

    public Map<String, Object> importBatch(String taskId, List<MultipartFile> files) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("导入任务ID不能为空");
        }
        GisFileOptRecordVO recordDetail = gisFileOptRecordService.getImportRecordDetail(taskId);
        taskId = recordDetail.getRecordNo();
        if (!"QUEUED".equals(recordDetail.getRecordStatus())
                || !"VALIDATING".equals(recordDetail.getCurrentStage())) {
            throw new IllegalStateException("当前导入任务不允许重复上传: " + taskId);
        }
        if (files == null || files.size() != recordDetail.getTotalCount()) {
            throw new IllegalArgumentException(
                    "实际文件数量必须与创建任务时的预计数量一致: " + recordDetail.getTotalCount());
        }
        GisDataSet dataSet = validateRequest(recordDetail.getDataSetId(), files);
        GisFileOptRecord record = new GisFileOptRecord();
        record.setId(recordDetail.getId());
        record.setRecordNo(recordDetail.getRecordNo());
        record.setDataSetId(recordDetail.getDataSetId());
        record.setTotalCount(recordDetail.getTotalCount());
        record.setCompletedCount(recordDetail.getCompletedCount());
        record.setFailedCount(recordDetail.getFailedCount());
        record.setRecordStatus(recordDetail.getRecordStatus());
        record.setCurrentStage(recordDetail.getCurrentStage());
        record.setVersion(recordDetail.getVersion());

        List<GisFileMeta> fileMetas = new ArrayList<>(files.size());
        List<GisStoredFile> storedFiles = new ArrayList<>(files.size());
        int completedCount = 0;
        int failedCount = 0;
        long totalBytes = files.stream()
                .filter(file -> file != null)
                .mapToLong(file -> Math.max(0L, file.getSize()))
                .sum();
        long processedBytes = 0L;
        int[] lastStorageProgress = {0};
        asyncTaskService.updateProgress(
                taskId, 0, "storing", "开始存储文件");

        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            GisFileMeta meta = newBaseMeta(recordDetail.getDataSetId(), file, index + 1);
            meta.setImportExportRecordId(recordDetail.getId());
            long completedBytesBeforeFile = processedBytes;
            GisStoredFile storedFile = null;
            try {
                validateFileCategory(dataSet, file);
                String currentFileName = meta.getOriginalName();
                storedFile = fileUploadUtil.store(
                        dataSet.getDataSetCode(), taskId, file, copiedBytes ->
                        reportStorageProgress(
                                recordDetail.getRecordNo(),
                                completedBytesBeforeFile + copiedBytes,
                                totalBytes,
                                currentFileName,
                                lastStorageProgress));

                meta.setStorageName(storedFile.getStorageName());
                meta.setStorageKey(storedFile.getStorageKey());
                meta.setStorageType(storedFile.getStorageType());
                meta.setExtension(FileUploadUtil.extensionOf(file.getOriginalFilename()));
                meta.setSizeBytes(storedFile.getSizeBytes());
                meta.setSha256(storedFile.getSha256());
                meta.setUploadStatus("READY");
                meta.setCleanupStatus("NOT_REQUIRED");
                storedFiles.add(storedFile);
                completedCount++;
            } catch (Exception ex) {
                failedCount++;
                boolean cleaned = true;
                String residualStorageName = null;
                String residualStorageKey = null;
                try {
                    if (storedFile != null) {
                        residualStorageName = storedFile.getStorageName();
                        residualStorageKey = storedFile.getStorageKey();
                        fileUploadUtil.deleteStored(residualStorageKey);
                    }
                } catch (Exception cleanupEx) {
                    cleaned = false;
                    ex.addSuppressed(cleanupEx);
                    log.warn("清理失败文件异常: taskId={}, file={}",
                            taskId, meta.getOriginalName(), cleanupEx);
                }
                meta.setStorageName(cleaned ? null : residualStorageName);
                meta.setStorageKey(cleaned ? null : residualStorageKey);
                meta.setUploadStatus("FAILED");
                meta.setCleanupStatus(cleaned ? "COMPLETED" : "FAILED");
                meta.setErrorMessage(abbreviate(ex.getMessage(), 1000));
                log.warn("GIS文件存储失败: taskId={}, file={}, error={}",
                        taskId, meta.getOriginalName(), ex.getMessage());
            }
            fileMetas.add(meta);
            processedBytes += file == null ? 0L : Math.max(0L, file.getSize());
            reportStorageProgress(
                    taskId, processedBytes, totalBytes, meta.getOriginalName(), lastStorageProgress);
        }

        try {
            gisFileOptRecordService.saveImportResult(
                    record, fileMetas, completedCount, failedCount);
        } catch (Exception ex) {
            for (GisStoredFile storedFile : storedFiles) {
                try {
                    fileUploadUtil.deleteStored(storedFile.getStorageKey());
                } catch (Exception cleanupEx) {
                    ex.addSuppressed(cleanupEx);
                    log.warn("回滚已存储文件失败: taskId={}, key={}",
                            taskId, storedFile.getStorageKey(), cleanupEx);
                }
            }
            GisFileOptRecordVO latest = gisFileOptRecordService.getImportRecordDetail(taskId);
            if ("QUEUED".equals(latest.getRecordStatus())
                    && "VALIDATING".equals(latest.getCurrentStage())) {
                gisFileOptRecordService.markPreparationFailed(record, ex.getMessage());
                asyncTaskService.finalizeTaskFailure(
                        taskId, "文件元数据批量入库失败: " + ex.getMessage());
            }
            throw ex;
        }

        asyncTaskService.updateProgressCounts(
                taskId, completedCount, failedCount, STORAGE_PROGRESS_END,
                "stored", "文件存储和元数据建档完成");
        asyncTaskService.finalizeTaskResult(
                taskId,
                failedCount == 0
                        ? "全部文件上传完成"
                        : "上传结束，成功" + completedCount + "个，失败" + failedCount + "个");

        String status = failedCount == 0
                ? "COMPLETED"
                : completedCount == 0 ? "FAILED" : "PARTIAL_FAILED";
        return buildResult(
                taskId, record.getDataSetId(), files.size(), completedCount, failedCount, status);
    }

    private GisFileMeta newBaseMeta(Long dataSetId, MultipartFile file, int index) {
        LocalDateTime now = LocalDateTime.now();
        GisFileMeta meta = new GisFileMeta();
        meta.setId(IdWorker.getId());
        meta.setDataSetId(dataSetId);
        meta.setOriginalName(displayFileName(file, index));
        meta.setExtension(extensionOrNull(meta.getOriginalName()));
        meta.setStorageType("LOCAL");
        meta.setSizeBytes(file == null ? 0L : Math.max(0L, file.getSize()));
        meta.setCleanupStatus("NOT_REQUIRED");
        meta.setCreateTime(now);
        meta.setUpdateTime(now);
        meta.setDeleted(0);
        return meta;
    }

    private void reportStorageProgress(
            String taskId,
            long stagedBytes,
            long totalBytes,
            String fileName,
            int[] lastProgress) {
        int progress = totalBytes <= 0
                ? STORAGE_PROGRESS_END
                : (int) Math.min(
                        STORAGE_PROGRESS_END,
                        stagedBytes * STORAGE_PROGRESS_END / totalBytes);
        if (progress <= lastProgress[0]) {
            return;
        }
        lastProgress[0] = progress;
        asyncTaskService.updateProgress(
                taskId,
                progress,
                "storing",
                "正在存储文件：" + fileName + "（"
                        + formatBytes(Math.min(stagedBytes, totalBytes)) + "/"
                        + formatBytes(totalBytes) + "）");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0d);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0d * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0d * 1024 * 1024));
    }

    private void validateFileCategory(GisDataSet dataSet, MultipartFile file) {
        FileUploadUtil.validateMultipartFile(file);
        String extension = FileUploadUtil.extensionOf(file.getOriginalFilename());
        Set<String> allowed = switch (dataSet.getCategoryId().intValue()) {
            case 0 -> IMAGE_EXTENSIONS;
            case 1 -> TERRAIN_EXTENSIONS;
            case 2 -> VECTOR_EXTENSIONS;
            default -> throw new IllegalArgumentException(
                    "未知GIS数据类别: " + dataSet.getCategoryId());
        };
        if (!allowed.contains(extension)) {
            throw new IllegalArgumentException(
                    "文件类型与数据集类别不匹配: " + file.getOriginalFilename());
        }
    }

    private Map<String, Object> buildResult(
            String taskId,
            Long dataSetId,
            int totalCount,
            int acceptedCount,
            int failedCount,
            String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("dataSetId", dataSetId);
        data.put("totalCount", totalCount);
        data.put("acceptedCount", acceptedCount);
        data.put("failedCount", failedCount);
        data.put("status", status);
        data.put("message", "文件存储和元数据建档已完成");
        return data;
    }

    private GisDataSet validateRequest(Long dataSetId, List<MultipartFile> files) {
        if (dataSetId == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("至少需要上传一个文件");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new IllegalArgumentException("单次上传文件数量不能超过" + MAX_FILES_PER_REQUEST);
        }

        long totalSize = 0;
        for (MultipartFile file : files) {
            long size = file == null ? 0 : Math.max(0, file.getSize());
            if (size > MAX_TOTAL_SIZE - totalSize) {
                throw new IllegalArgumentException("单次上传文件总大小不能超过5GB");
            }
            totalSize += size;
        }

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

    private String displayFileName(MultipartFile file, int index) {
        if (file == null || file.getOriginalFilename() == null
                || file.getOriginalFilename().isBlank()) {
            return "__unnamed_file_" + index;
        }
        try {
            String name = FileUploadUtil.safeOriginalName(file.getOriginalFilename());
            return name.length() <= 255 ? name : name.substring(0, 255);
        } catch (Exception ex) {
            return "__invalid_file_" + index;
        }
    }

    private String extensionOrNull(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 1 || dot == fileName.length() - 1) {
            return null;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,16}") ? extension : null;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
