package org.ocean.admin.gis.scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.service.FileUploadService;
import org.ocean.admin.gis.service.FileMetaService;
import org.ocean.admin.gis.service.VectorResourceService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期回收长时间无活动的 GIS 上传暂存资源。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadCleanupJob {

    private final FileUploadService fileUploadService;
    private final FileMetaService fileMetaService;
    private final VectorResourceService vectorResources;

    @Scheduled(
            fixedDelayString = "#{@fileUploadConfig.cleanupInterval.toMillis()}",
            initialDelayString = "#{@fileUploadConfig.cleanupInterval.toMillis()}")
    public void execute() {
        try {
            fileMetaService.cleanupReleasedRawFiles();
            vectorResources.cleanupRetired();
            int cleanedCount = fileUploadService.cleanupExpiredBatches();
            if (cleanedCount > 0) {
                log.info("GIS上传暂存资源定时清理完成: cleanedCount={}", cleanedCount);
            }
        } catch (RuntimeException ex) {
            log.error("GIS上传暂存资源定时清理失败", ex);
        }
    }

    /** 释放意图提交后立即尝试回收，失败记录继续由定时任务重试。 */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRawFilesReleased(FileMetaService.RawFilesReleased event) {
        fileMetaService.cleanupReleasedRawFiles();
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onResourceRetired(VectorResourceService.ResourceRetired event) {
        vectorResources.cleanupRetired();
    }
}
