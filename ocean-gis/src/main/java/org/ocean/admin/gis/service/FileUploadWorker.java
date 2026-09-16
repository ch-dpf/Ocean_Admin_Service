package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.UploadTask;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.GisUploadFileItem;
import org.ocean.admin.gis.entity.GisImportExportRecord;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** GIS 上传后台执行器。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadWorker {

    private final FileUploadUtil fileUploadUtil;
    private final GisFileMetaService gisFileMetaService;
    private final GisDataSetService gisDataSetService;
    private final GisTaskLifecycleService taskLifecycle;
    private final GisFileOptRecordService fileOptRecordService;
    private final TaskProgressService progressService;

    /** 不创建 gis_task，使用导入记录编号异步转存并推送逐文件进度。 */
    @Async("gisTaskExecutor")
    public void processImport(
            Long recordId,
            String recordNo,
            Long dataSetId,
            String dataSetCode,
            int totalCount,
            java.util.List<GisUploadFileItem> files) {
        int initialFailed = totalCount - files.size();
        try {
            fileOptRecordService.markImportRunning(recordId);
            progressService.updateProgress(recordNo,
                    percent(initialFailed, totalCount),
                    "transferring",
                    "开始正式转存文件");
        } catch (Exception ex) {
            closeFailedImport(recordId, recordNo, files, ex);
            return;
        }

        for (GisUploadFileItem item : files) {
            try {
                boolean success = processImportFile(recordId, recordNo, dataSetId, dataSetCode, item);
                progressService.updateProgress(recordNo, success);
            } catch (Exception ex) {
                closeFailedImport(recordId, recordNo, files, ex);
                return;
            }
        }

        try {
            GisImportExportRecord finished = fileOptRecordService.finishImport(recordId);
            progressService.finalizeTaskResult(recordNo,
                    "COMPLETED".equals(finished.getRecordStatus())
                            ? "全部文件导入完成"
                            : "导入结束，成功" + finished.getCompletedCount()
                            + "个，失败" + finished.getFailedCount() + "个");
        } catch (Exception ex) {
            progressService.finalizeTaskFailure(recordNo, "导入记录收口失败: " + ex.getMessage());
            log.error("GIS导入记录收口失败: recordNo={}", recordNo, ex);
        }
    }

    @Async("gisTaskExecutor")
    public void process(UploadTask upload) {
        long successCount = 0;
        try {
            taskLifecycle.start(upload.taskId(), upload.taskNo(), 0, "开始处理上传文件");

            for (GisUploadFileItem item : upload.files()) {
                boolean success = processFile(upload, item);
                if (success) {
                    successCount++;
                }
                taskLifecycle.recordResult(upload.taskId(), upload.taskNo(), success);
            }

            gisDataSetService.incrementFileCount(upload.dataSetId(), successCount);
            taskLifecycle.finish(upload.taskId(), upload.taskNo(),
                    finished -> "COMPLETED".equals(finished.getTaskStatus())
                            ? "全部文件上传完成"
                            : "上传结束，成功" + finished.getCompletedCount()
                            + "个，失败" + finished.getFailedCount() + "个");
        } catch (Exception ex) {
            upload.files().forEach(item -> {
                try {
                    fileUploadUtil.deleteStaged(item.stagedFile().getStagingKey());
                } catch (Exception cleanupException) {
                    log.warn("清理未处理的暂存文件失败: {}",
                            item.stagedFile().getStagingKey(), cleanupException);
                }
            });
            gisFileMetaService.markTaskPendingFilesFailed(upload.taskId(), ex.getMessage());
            taskLifecycle.fail(upload.taskId(), upload.taskNo(), "上传任务失败: ", ex);
            log.error("GIS上传任务执行失败: taskNo={}", upload.taskNo(), ex);
        }
    }

    private boolean processFile(UploadTask upload, GisUploadFileItem item) {
        GisStoredFile storedFile = null;
        try {
            storedFile = fileUploadUtil.commit(
                    upload.dataSetCode(), upload.taskNo(), item.stagedFile());
            gisFileMetaService.markReady(item.fileMetaId(), storedFile);
            return true;
        } catch (Exception ex) {
            cleanupFailedFile(item, storedFile);
            gisFileMetaService.markFailed(item.fileMetaId(), ex.getMessage());
            log.error("GIS文件上传失败: taskNo={}, file={}",
                    upload.taskNo(), item.stagedFile().getOriginalName(), ex);
            return false;
        }
    }

    private boolean processImportFile(
            Long recordId,
            String recordNo,
            Long dataSetId,
            String dataSetCode,
            GisUploadFileItem item) {
        GisStoredFile storedFile = null;
        try {
            storedFile = fileUploadUtil.commit(dataSetCode, recordNo, item.stagedFile());
            fileOptRecordService.markImportFileReady(
                    recordId, dataSetId, item.fileMetaId(), storedFile);
            return true;
        } catch (Exception ex) {
            boolean cleaned = cleanupImportFile(item, storedFile);
            try {
                fileOptRecordService.markImportFileFailed(
                        recordId, item.fileMetaId(), ex.getMessage(), cleaned, storedFile);
            } catch (Exception persistEx) {
                ex.addSuppressed(persistEx);
                throw ex;
            }
            log.error("GIS导入文件转存失败: recordNo={}, file={}",
                    recordNo, item.stagedFile().getOriginalName(), ex);
            return false;
        }
    }

    private void failUnprocessedImportFiles(
            Long recordId,
            String recordNo,
            java.util.List<GisUploadFileItem> files,
            Exception cause) {
        for (GisUploadFileItem item : files) {
            boolean cleaned = cleanupImportFile(item, null);
            try {
                fileOptRecordService.markImportFileFailed(
                        recordId, item.fileMetaId(), cause.getMessage(), cleaned, null);
                progressService.updateProgress(recordNo, false);
            } catch (Exception ignored) {
                log.warn("GIS导入未处理文件收口失败: recordNo={}, fileMetaId={}",
                        recordNo, item.fileMetaId(), ignored);
            }
        }
    }

    private void closeFailedImport(
            Long recordId,
            String recordNo,
            java.util.List<GisUploadFileItem> files,
            Exception cause) {
        failUnprocessedImportFiles(recordId, recordNo, files, cause);
        try {
            fileOptRecordService.finishImport(recordId);
        } catch (Exception finishEx) {
            cause.addSuppressed(finishEx);
        }
        progressService.finalizeTaskFailure(recordNo, "文件导入失败: " + cause.getMessage());
        log.error("GIS导入异步转存失败: recordNo={}", recordNo, cause);
    }

    private boolean cleanupImportFile(GisUploadFileItem item, GisStoredFile storedFile) {
        try {
            if (storedFile == null) {
                fileUploadUtil.deleteStaged(item.stagedFile().getStagingKey());
            } else {
                fileUploadUtil.deleteStored(storedFile.getStorageKey());
            }
            return true;
        } catch (Exception cleanupEx) {
            log.warn("清理导入失败文件异常: file={}",
                    item.stagedFile().getOriginalName(), cleanupEx);
            return false;
        }
    }

    private int percent(int processed, int total) {
        return total == 0 ? 0 : (int) ((processed * 100L) / total);
    }

    private void cleanupFailedFile(GisUploadFileItem item, GisStoredFile storedFile) {
        try {
            if (storedFile != null) {
                fileUploadUtil.deleteStored(storedFile.getStorageKey());
            } else {
                fileUploadUtil.deleteStaged(item.stagedFile().getStagingKey());
            }
        } catch (Exception cleanupEx) {
            log.warn("清理失败文件异常: file={}",
                    item.stagedFile().getOriginalName(), cleanupEx);
        }
    }
}
