package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.TempFile;
import org.ocean.admin.gis.dto.GisUploadFileItem;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisImportExportRecord;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisImportTaskVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final GisFileOptRecordService fileOptService;
    private final FileUploadWorker fileUploadWorker;
    private final AsyncTaskService asyncTaskService;

    public GisImportTaskVO importBatch(Long dataSetId, List<MultipartFile> files) {
        // 输入校验
        GisDataSet dataSet = validateRequest(dataSetId, files);
        GisImportExportRecord record = fileOptService.createImportRecord(dataSetId, files.size());
        registerImportProgress(record, dataSet);

        // MultipartFile文件转存
        List<GisFileMeta> fileMetas = new ArrayList<>(files.size());
        List<TempFile> tempFiles = new ArrayList<>(files.size());
        List<GisUploadFileItem> uploadItems = new ArrayList<>(files.size());
        int pendingCount = 0;
        int failedCount = 0;

        for (int index = 0; index < files.size(); index++) {
            PreparedFile prepared = prepareFile(record, dataSet, files.get(index), index + 1);
            fileMetas.add(prepared.fileMeta());
            if (prepared.stagedFile() != null) {
                tempFiles.add(prepared.stagedFile());
                uploadItems.add(new GisUploadFileItem(
                        prepared.fileMeta().getId(), prepared.stagedFile()));
                pendingCount++;
            } else {
                failedCount++;
            }
            asyncTaskService.updateProgress(
                    record.getRecordNo(),
                    0,
                    "staging",
                    "正在暂存文件（" + (index + 1) + "/" + files.size() + "）："
                            + prepared.fileMeta().getOriginalName());
        }

        try {
            fileOptService.savePreparedFiles(record, fileMetas, pendingCount, failedCount);
        } catch (Exception ex) {
            tempFiles.forEach(file -> deleteStagedQuietly(file, record.getRecordNo()));
            try {
                fileOptService.markPreparationFailed(record, ex.getMessage());
            } catch (Exception finalizeEx) {
                ex.addSuppressed(finalizeEx);
            }
            asyncTaskService.finalizeTaskFailure(
                    record.getRecordNo(), "导入准备失败: " + ex.getMessage());
            throw ex;
        }

        dispatchImport(record, dataSet, uploadItems, failedCount);

        log.info("GIS导入请求建档完成: recordNo={}, dataSetId={}, pending={}, failed={}",
                record.getRecordNo(), dataSetId, pendingCount, failedCount);
        return GisImportTaskVO.builder()
                .recordId(record.getId())
                .recordNo(record.getRecordNo())
                .dataSetId(dataSetId)
                .totalCount(files.size())
                .acceptedCount(pendingCount)
                .failedCount(failedCount)
                .status(record.getRecordStatus())
                .build();
    }

    private void registerImportProgress(GisImportExportRecord record, GisDataSet dataSet) {
        try {
            asyncTaskService.registerTask(
                    record.getRecordNo(),
                    "导入数据集：" + dataSet.getDataSetName(),
                    record.getTotalCount(),
                    "GIS_IMPORT");
            asyncTaskService.updateProgress(
                    record.getRecordNo(), 0, "staging", "开始校验并暂存上传文件");
        } catch (Exception ex) {
            try {
                fileOptService.markPreparationFailed(record, ex.getMessage());
            } catch (Exception finalizeEx) {
                ex.addSuppressed(finalizeEx);
            }
            throw ex;
        }
    }

    private void dispatchImport(
            GisImportExportRecord record,
            GisDataSet dataSet,
            List<GisUploadFileItem> uploadItems,
            int failedCount) {
        String recordNo = record.getRecordNo();
        try {
            asyncTaskService.updateProgressCounts(
                    recordNo,
                    0,
                    failedCount,
                    "staged",
                    "文件暂存和元数据建档完成");
            if (uploadItems.isEmpty()) {
                asyncTaskService.finalizeTaskResult(recordNo, "没有可导入的有效文件");
                return;
            }
            fileUploadWorker.processImport(
                    record.getId(),
                    recordNo,
                    dataSet.getId(),
                    dataSet.getDataSetCode(),
                    record.getTotalCount(),
                    List.copyOf(uploadItems));
        } catch (Exception ex) {
            for (GisUploadFileItem item : uploadItems) {
                boolean cleaned = deleteStagedQuietly(item.stagedFile(), recordNo);
                try {
                    fileOptService.markImportFileFailed(
                            record.getId(), item.fileMetaId(), ex.getMessage(), cleaned, null);
                } catch (Exception persistEx) {
                    ex.addSuppressed(persistEx);
                }
            }
            try {
                fileOptService.finishImport(record.getId());
            } catch (Exception finishEx) {
                ex.addSuppressed(finishEx);
            }
            asyncTaskService.finalizeTaskFailure(recordNo, "导入任务启动失败: " + ex.getMessage());
            throw ex;
        }
    }

    private PreparedFile prepareFile(
            GisImportExportRecord record,
            GisDataSet dataSet,
            MultipartFile file,
            int fileIndex) {
        LocalDateTime now = LocalDateTime.now();
        GisFileMeta meta = new GisFileMeta();
        meta.setId(IdWorker.getId());
        meta.setDataSetId(dataSet.getId());
        meta.setImportExportRecordId(record.getId());
        meta.setOriginalName(displayFileName(file, fileIndex));
        meta.setStorageType("LOCAL");
        meta.setExtension(extensionOrNull(meta.getOriginalName()));
        meta.setSizeBytes(file == null ? 0L : Math.max(0L, file.getSize()));
        meta.setTaskId(null);
        meta.setCleanupStatus("NOT_REQUIRED");
        meta.setCreateTime(now);
        meta.setUpdateTime(now);
        meta.setDeleted(0);

        try {
            validateFile(dataSet, file);
            TempFile staged = fileUploadUtil.stage(record.getRecordNo(), file);
            meta.setOriginalName(staged.getOriginalName());
            meta.setStorageName(staged.getStorageName());
            meta.setStorageKey(staged.getStagingKey());
            meta.setExtension(staged.getExtension());
            meta.setSizeBytes(staged.getSizeBytes());
            meta.setSha256(staged.getSha256());
            meta.setUploadStatus("PENDING");
            return new PreparedFile(meta, staged);
        } catch (Exception ex) {
            meta.setStorageName(null);
            meta.setStorageKey(null);
            meta.setSha256(null);
            meta.setUploadStatus("FAILED");
            meta.setErrorMessage(abbreviate(ex.getMessage(), 1000));
            log.warn("GIS导入文件准备失败: recordNo={}, file={}, error={}",
                    record.getRecordNo(), meta.getOriginalName(), ex.getMessage());
            return new PreparedFile(meta, null);
        }
    }

    /**
     * 校验请求与数据集
     */
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

    private void validateFile(GisDataSet dataSet, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        String displayName = normalizedFileName(name);
        if (displayName.length() > 255) {
            throw new IllegalArgumentException("上传文件名长度不能超过255个字符");
        }
        String extension = extensionOrNull(name);
        if (extension == null) {
            throw new IllegalArgumentException("文件扩展名不能为空: " + name);
        }
        Set<String> allowed = switch (dataSet.getCategoryId().intValue()) {
            case 0 -> IMAGE_EXTENSIONS;
            case 1 -> TERRAIN_EXTENSIONS;
            case 2 -> VECTOR_EXTENSIONS;
            default -> throw new IllegalArgumentException("未知GIS数据类别: " + dataSet.getCategoryId());
        };
        if (!allowed.contains(extension)) {
            throw new IllegalArgumentException(
                    "文件类型与数据集类别不匹配: " + name + ", categoryId=" + dataSet.getCategoryId());
        }
    }

    private String displayFileName(MultipartFile file, int fileIndex) {
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            return "__unnamed_file_" + fileIndex;
        }
        String normalized = file.getOriginalFilename().replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            return "__unnamed_file_" + fileIndex;
        }
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    private String normalizedFileName(String originalName) {
        String normalized = originalName.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1).trim();
    }

    private String extensionOrNull(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 1 || dot == fileName.length() - 1) {
            return null;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,16}") ? extension : null;
    }

    private boolean deleteStagedQuietly(TempFile file, String recordNo) {
        try {
            fileUploadUtil.deleteStaged(file.getStagingKey());
            return true;
        } catch (Exception cleanupEx) {
            log.warn("清理上传临时文件失败: recordNo={}, key={}",
                    recordNo, file.getStagingKey(), cleanupEx);
            return false;
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record PreparedFile(GisFileMeta fileMeta, TempFile stagedFile) {
    }
}
