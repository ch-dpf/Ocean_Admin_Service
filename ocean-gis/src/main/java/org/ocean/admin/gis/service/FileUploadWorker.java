package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.TempFile;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/** GIS 上传后台执行器。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadWorker {

    private final AsyncTaskService asyncTaskService;
    private final GisFileOptRecordService gisFileOptRecordService;
    private final FileUploadUtil fileUploadUtil;

    public record PendingUploadFile(Long fileMetaId, TempFile stagedFile) {
    }

    public record BatchUploadContext(
            String taskId,
            Long recordId,
            Long dataSetId,
            String dataSetCode,
            int totalCount,
            int initialFailedCount,
            List<PendingUploadFile> files) {
    }

    @Async("gisTaskExecutor")
    public void asyncUploadBatchFiles(BatchUploadContext context) {
        if (context == null || context.files() == null || context.files().isEmpty()) {
            return;
        }

        int successCount = 0;
        int transferFailedCount = 0;
        try {
            gisFileOptRecordService.markImportRunning(context.recordId());
            for (int index = 0; index < context.files().size(); index++) {
                PendingUploadFile file = context.files().get(index);
                boolean success = processUploadedFile(
                        context,
                        file,
                        context.initialFailedCount() + index,
                        context.totalCount());
                if (success) {
                    successCount++;
                } else {
                    transferFailedCount++;
                }
                asyncTaskService.updateProgress(context.taskId(), success);
            }

            int totalFailed = context.initialFailedCount() + transferFailedCount;
            gisFileOptRecordService.finishImport(context.recordId());
            asyncTaskService.finalizeTaskResult(
                    context.taskId(),
                    totalFailed == 0
                            ? "全部文件上传完成"
                            : "上传结束，成功" + successCount + "个，失败" + totalFailed + "个");
        } catch (Exception ex) {
            if (successCount == 0 && transferFailedCount == 0) {
                for (PendingUploadFile file : context.files()) {
                    boolean cleaned = cleanupFailedFile(file, null, ex);
                    try {
                        gisFileOptRecordService.markImportFileFailed(
                                context.recordId(), file.fileMetaId(),
                                ex.getMessage(), cleaned, null);
                        asyncTaskService.updateProgress(context.taskId(), false);
                    } catch (Exception persistEx) {
                        ex.addSuppressed(persistEx);
                    }
                }
                try {
                    gisFileOptRecordService.finishImport(context.recordId());
                } catch (Exception persistEx) {
                    ex.addSuppressed(persistEx);
                }
            }
            asyncTaskService.finalizeTaskFailure(
                    context.taskId(), "批量上传执行失败: " + ex.getMessage());
            log.error("GIS批量上传执行失败: taskId={}", context.taskId(), ex);
        }
    }

    private boolean processUploadedFile(
            BatchUploadContext context,
            PendingUploadFile file,
            int fileIndex,
            int totalFiles) {
        GisStoredFile storedFile = null;
        try {
            reportUploadProgress(
                    context.taskId(), fileIndex, totalFiles, 5,
                    "saving", "正在保存文件：" + file.stagedFile().getOriginalName());
            storedFile = fileUploadUtil.commit(
                    context.dataSetCode(), context.taskId(), file.stagedFile());
            gisFileOptRecordService.markImportFileReady(
                    context.recordId(), context.dataSetId(), file.fileMetaId(), storedFile);
            reportUploadProgress(
                    context.taskId(), fileIndex, totalFiles, 100,
                    "completed", "已完成：" + file.stagedFile().getOriginalName());
            log.info("文件上传完成: taskId={}, fileName={}, fileId={}",
                    context.taskId(), file.stagedFile().getOriginalName(), file.fileMetaId());
            return true;
        } catch (Exception ex) {
            boolean cleaned = cleanupFailedFile(file, storedFile, ex);
            try {
                gisFileOptRecordService.markImportFileFailed(
                        context.recordId(), file.fileMetaId(), ex.getMessage(), cleaned, storedFile);
            } catch (Exception persistEx) {
                ex.addSuppressed(persistEx);
                log.error("GIS文件失败状态更新异常: taskId={}, fileMetaId={}",
                        context.taskId(), file.fileMetaId(), persistEx);
            }
            log.error("GIS文件上传失败: taskId={}, file={}",
                    context.taskId(), file.stagedFile().getOriginalName(), ex);
            return false;
        }
    }

    private boolean cleanupFailedFile(
            PendingUploadFile file,
            GisStoredFile storedFile,
            Exception cause) {
        try {
            if (storedFile != null) {
                fileUploadUtil.deleteStored(storedFile.getStorageKey());
            } else {
                fileUploadUtil.deleteStaged(file.stagedFile().getStagingKey());
            }
            return true;
        } catch (Exception cleanupEx) {
            cause.addSuppressed(cleanupEx);
            log.warn("清理失败文件异常: file={}",
                    file.stagedFile().getOriginalName(), cleanupEx);
            return false;
        }
    }

    private void reportUploadProgress(
            String taskId,
            int fileIndex,
            int totalFiles,
            int stagePercent,
            String stage,
            String message) {
        if (taskId == null || totalFiles <= 0) {
            return;
        }
        int safeFileIndex = Math.max(0, fileIndex);
        int safeStagePercent = Math.max(0, Math.min(100, stagePercent));
        int progress = (int) Math.round(
                (((double) safeFileIndex) + safeStagePercent / 100.0d)
                        / Math.max(1, totalFiles) * 100.0d);
        asyncTaskService.updateTaskProgress(
                taskId,
                Math.max(0, Math.min(100, progress)),
                stage,
                message,
                "running",
                false);
    }
}
