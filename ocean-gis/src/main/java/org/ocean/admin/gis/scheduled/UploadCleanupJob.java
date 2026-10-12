package org.ocean.admin.gis.scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.service.FileUploadService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期回收长时间无活动的 GIS 上传暂存资源。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadCleanupJob {

    private final FileUploadService fileUploadService;

    @Scheduled(
            fixedDelayString = "#{@fileUploadConfig.cleanupInterval.toMillis()}",
            initialDelayString = "#{@fileUploadConfig.cleanupInterval.toMillis()}")
    public void execute() {
        try {
            int cleanedCount = fileUploadService.cleanupExpiredBatches();
            if (cleanedCount > 0) {
                log.info("GIS上传暂存资源定时清理完成: cleanedCount={}", cleanedCount);
            }
        } catch (RuntimeException ex) {
            log.error("GIS上传暂存资源定时清理失败", ex);
        }
    }
}
