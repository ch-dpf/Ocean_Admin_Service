package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisPreparedUpload;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.GisUploadFileItem;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** GIS 上传后台执行器。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadWorker {

    private final FileStorageService fileStorageService;
    private final GisFileMetaService gisFileMetaService;
    private final GisTaskService gisTaskService;
    private final GisDataSetService gisDataSetService;
    private final TaskProgressService taskProgressService;

    @Async("gisTaskExecutor")
    public void process(GisPreparedUpload upload) {
        long successCount = 0;
        try {
            gisTaskService.markRunning(upload.taskId());
            taskProgressService.updateProgress(
                    upload.taskNo(), 0, "processing", "开始处理上传文件");

            for (GisUploadFileItem item : upload.files()) {
                boolean success = processFile(upload, item);
                if (success) {
                    successCount++;
                    gisTaskService.incrementCompleted(upload.taskId());
                } else {
                    gisTaskService.incrementFailed(upload.taskId());
                }
                taskProgressService.updateProgress(upload.taskNo(), success);
            }

            gisDataSetService.incrementFileCount(upload.dataSetId(), successCount);
            GisTask finished = gisTaskService.finish(upload.taskId());
            taskProgressService.finalizeTaskResult(
                    upload.taskNo(),
                    "COMPLETED".equals(finished.getTaskStatus())
                            ? "全部文件上传完成"
                            : "上传结束，成功" + finished.getCompletedCount()
                            + "个，失败" + finished.getFailedCount() + "个");
        } catch (Exception ex) {
            upload.files().forEach(item -> {
                try {
                    fileStorageService.deleteStaged(item.stagedFile().getStagingKey());
                } catch (Exception cleanupException) {
                    log.warn("清理未处理的暂存文件失败: {}",
                            item.stagedFile().getStagingKey(), cleanupException);
                }
            });
            gisFileMetaService.markTaskPendingFilesFailed(upload.taskId(), ex.getMessage());
            gisTaskService.markFailed(upload.taskId(), ex.getMessage());
            taskProgressService.finalizeTaskFailure(
                    upload.taskNo(), "上传任务失败: " + ex.getMessage());
            log.error("GIS上传任务执行失败: taskNo={}", upload.taskNo(), ex);
        }
    }

    private boolean processFile(GisPreparedUpload upload, GisUploadFileItem item) {
        GisStoredFile storedFile = null;
        try {
            storedFile = fileStorageService.commit(
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

    private void cleanupFailedFile(GisUploadFileItem item, GisStoredFile storedFile) {
        try {
            if (storedFile != null) {
                fileStorageService.deleteStored(storedFile.getStorageKey());
            } else {
                fileStorageService.deleteStaged(item.stagedFile().getStagingKey());
            }
        } catch (Exception cleanupEx) {
            log.warn("清理失败文件异常: file={}",
                    item.stagedFile().getOriginalName(), cleanupEx);
        }
    }
}
