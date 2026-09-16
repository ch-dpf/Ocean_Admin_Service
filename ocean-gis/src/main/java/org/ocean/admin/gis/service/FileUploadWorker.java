package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.UploadTask;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.GisUploadFileItem;
import org.ocean.admin.gis.util.FileUploadUtil;
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
