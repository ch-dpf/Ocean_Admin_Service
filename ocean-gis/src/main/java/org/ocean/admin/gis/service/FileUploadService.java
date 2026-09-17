package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.TempFile;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisImportExportRecord;
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
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "tif", "tiff", "img", "jp2", "png", "jpg", "jpeg", "tfw", "prj", "aux", "xml");
    private static final Set<String> TERRAIN_EXTENSIONS = Set.of(
            "tif", "tiff", "dem", "hgt", "terrain", "asc", "prj", "xml");
    private static final Set<String> VECTOR_EXTENSIONS = Set.of(
            "shp", "shx", "dbf", "prj", "cpg", "sbn", "sbx", "geojson", "json", "kml", "kmz", "gpkg");

    private final GisDataSetMapper gisDataSetMapper;
    private final FileUploadUtil fileUploadUtil;
    private final FileUploadWorker fileUploadWorker;
    private final AsyncTaskService asyncTaskService;
    private final GisFileOptRecordService gisFileOptRecordService;

    public Map<String, Object> processRegistry(Long dataSetId, Integer totalCount) {
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

        GisImportExportRecord record = gisFileOptRecordService.createImportRecord(
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
        GisImportExportRecord record = new GisImportExportRecord();
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
        List<FileUploadWorker.PendingUploadFile> pendingFiles = new ArrayList<>(files.size());
        int failedCount = 0;

        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            GisFileMeta meta = newBaseMeta(recordDetail.getDataSetId(), file, index + 1);
            meta.setImportExportRecordId(recordDetail.getId());
            try {
                validateFileCategory(dataSet, file);
                TempFile stagedFile = fileUploadUtil.stage(taskId, file);

                meta.setOriginalName(stagedFile.getOriginalName());
                meta.setStorageName(stagedFile.getStorageName());
                meta.setStorageKey(stagedFile.getStagingKey());
                meta.setExtension(stagedFile.getExtension());
                meta.setSizeBytes(stagedFile.getSizeBytes());
                meta.setSha256(stagedFile.getSha256());
                meta.setUploadStatus("PENDING");
                pendingFiles.add(new FileUploadWorker.PendingUploadFile(meta.getId(), stagedFile));
            } catch (Exception ex) {
                failedCount++;
                meta.setStorageName(null);
                meta.setStorageKey(null);
                meta.setUploadStatus("FAILED");
                meta.setCleanupStatus("COMPLETED");
                meta.setErrorMessage(abbreviate(ex.getMessage(), 1000));
                log.warn("GIS文件暂存失败: taskId={}, file={}, error={}",
                        taskId, meta.getOriginalName(), ex.getMessage());
            }
            fileMetas.add(meta);
            int progress = (int) (((index + 1L) * 30) / files.size());
            asyncTaskService.updateProgress(taskId, progress, "staging",
                    "正在暂存文件（" + (index + 1) + "/" + files.size() + "）："
                            + meta.getOriginalName());
        }

        try {
            gisFileOptRecordService.savePreparedFiles(
                    record, fileMetas, pendingFiles.size(), failedCount);
        } catch (Exception ex) {
            for (FileUploadWorker.PendingUploadFile file : pendingFiles) {
                deleteStagedQuietly(file.stagedFile(), taskId);
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
                taskId, 0, failedCount, "staged", "文件暂存和元数据建档完成");

        if (pendingFiles.isEmpty()) {
            asyncTaskService.finalizeTaskResult(taskId, "所有文件均未通过校验或暂存失败");
            return buildResult(
                    taskId, record.getDataSetId(), files.size(), 0, failedCount, "FAILED");
        }

        FileUploadWorker.BatchUploadContext context = new FileUploadWorker.BatchUploadContext(
                taskId,
                record.getId(),
                record.getDataSetId(),
                dataSet.getDataSetCode(),
                files.size(),
                failedCount,
                List.copyOf(pendingFiles));
        try {
            fileUploadWorker.asyncUploadBatchFiles(context);
        } catch (RuntimeException ex) {
            closeDispatchFailure(taskId, record.getId(), pendingFiles, ex);
            throw ex;
        }

        return buildResult(
                taskId, record.getDataSetId(), files.size(), pendingFiles.size(), failedCount, "QUEUED");
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

    private void closeDispatchFailure(
            String taskId,
            Long recordId,
            List<FileUploadWorker.PendingUploadFile> pendingFiles,
            RuntimeException cause) {
        for (FileUploadWorker.PendingUploadFile file : pendingFiles) {
            boolean cleaned = deleteStagedQuietly(file.stagedFile(), taskId);
            try {
                gisFileOptRecordService.markImportFileFailed(
                        recordId, file.fileMetaId(), cause.getMessage(), cleaned, null);
            } catch (Exception persistEx) {
                cause.addSuppressed(persistEx);
            }
        }
        try {
            gisFileOptRecordService.finishImport(recordId);
        } catch (Exception persistEx) {
            cause.addSuppressed(persistEx);
        }
        asyncTaskService.finalizeTaskFailure(taskId, "异步上传任务提交失败: " + cause.getMessage());
    }

    private boolean deleteStagedQuietly(TempFile file, String taskId) {
        try {
            fileUploadUtil.deleteStaged(file.getStagingKey());
            return true;
        } catch (Exception ex) {
            log.warn("清理暂存文件失败: taskId={}, key={}", taskId, file.getStagingKey(), ex);
            return false;
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
        data.put("message", "GIS文件批量导入任务已创建，请在消息中查看进度");
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
