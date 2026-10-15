package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisCreateTileSetArchiveRequest;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisTileSetArchive;
import org.ocean.admin.gis.mapper.GisTileSetArchiveMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.util.FileDownloadUtil;
import org.ocean.admin.gis.vo.GisTileSetArchiveVO;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** 静态瓦片集归档的创建、查询、状态流转与下载门面。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TileSetArchiveService {
    private static final String ARCHIVE_FORMAT = "ZIP";

    private final GisTileSetArchiveMapper archiveMapper;
    private final GisTileSetMapper tileSetMapper;
    private final ProcessingTaskRecordService taskService;
    private final GisProcessingStorageService storageService;
    private final FileDownloadUtil fileDownloadUtil;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public GisTileSetArchiveVO createArchive(GisCreateTileSetArchiveRequest request) {
        if (request == null || request.processingTaskId() == null) {
            throw new IllegalArgumentException("处理任务ID不能为空");
        }
        log.info("收到静态瓦片集归档请求: processingTaskId={}", request.processingTaskId());
        GisProcessingTask task = taskService.getRequired(request.processingTaskId());
        GisTileSet tileSet = requireArchivableTileSet(task);
        GisTileSetArchive existing = findByTileSetId(tileSet.getId());
        if (existing != null) {
            log.info("复用已有静态瓦片集归档: processingTaskId={}, taskNo={}, "
                            + "tileSetId={}, archiveNo={}, archiveStatus={}",
                    task.getId(), task.getTaskNo(), tileSet.getId(), existing.getArchiveNo(),
                    existing.getArchiveStatus());
            return getArchiveDetail(existing.getArchiveNo());
        }

        LocalDateTime now = LocalDateTime.now();
        GisTileSetArchive archive = new GisTileSetArchive();
        archive.setArchiveNo(TaskNoGenerator.generateArchiveTaskNo());
        archive.setProcessingTaskId(task.getId());
        archive.setTileSetId(tileSet.getId());
        archive.setArchiveName(buildArchiveName(task));
        archive.setArchiveFormat(ARCHIVE_FORMAT);
        archive.setArchiveStatus(TaskStatus.QUEUED);
        archive.setCurrentStage("QUEUED");
        archive.setTotalFileCount(0L);
        archive.setProcessedFileCount(0L);
        archive.setTotalBytes(0L);
        archive.setProcessedBytes(0L);
        archive.setArchiveSizeBytes(0L);
        archive.setOperatorId(currentOperatorId());
        archive.setDownloadCount(0L);
        archive.setVersion(0L);
        archive.setCreateTime(now);
        archive.setUpdateTime(now);
        archive.setDeleted(0);
        if (archiveMapper.insert(archive) != 1) {
            throw new IllegalStateException("静态瓦片集归档记录创建失败");
        }
        eventPublisher.publishEvent(new TileSetArchiveRequestedEvent(archive.getId()));
        log.info("静态瓦片集归档已入队: archiveId={}, archiveNo={}, processingTaskId={}, "
                        + "taskNo={}, tileSetId={}, operatorId={}",
                archive.getId(), archive.getArchiveNo(), task.getId(), task.getTaskNo(),
                tileSet.getId(), archive.getOperatorId());
        return getArchiveDetail(archive.getArchiveNo());
    }

    public PageResult<List<GisTileSetArchiveVO>> getArchivePage(
            Integer current, Integer size, String archiveNo, String taskNo, String taskName,
            String processingType, String archiveStatus,
            LocalDateTime createTimeStart, LocalDateTime createTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        if (createTimeStart != null && createTimeEnd != null
                && createTimeStart.isAfter(createTimeEnd)) {
            throw new IllegalArgumentException("创建开始时间不能晚于创建结束时间");
        }
        Page<GisTileSetArchiveVO> page = archiveMapper.selectArchivePage(
                new Page<>(currentPage, pageSize), trimToNull(archiveNo), trimToNull(taskNo),
                trimToNull(taskName), normalizeProcessingType(processingType),
                normalizeArchiveStatus(archiveStatus), createTimeStart, createTimeEnd);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords());
    }

    public GisTileSetArchiveVO getArchiveDetail(String archiveNo) {
        String normalizedArchiveNo = normalizeArchiveNo(archiveNo);
        GisTileSetArchiveVO archive = archiveMapper.selectArchiveByNo(normalizedArchiveNo);
        if (archive == null) {
            throw new IllegalArgumentException("静态瓦片集归档不存在: " + normalizedArchiveNo);
        }
        return archive;
    }

    @Transactional(rollbackFor = Exception.class)
    public GisTileSetArchiveVO retryArchive(String archiveNo) {
        GisTileSetArchive archive = getRequiredByNo(archiveNo);
        log.info("收到静态瓦片集归档重试请求: archiveId={}, archiveNo={}, currentStatus={}",
                archive.getId(), archive.getArchiveNo(), archive.getArchiveStatus());
        if (archive.getArchiveStatus() != TaskStatus.FAILED) {
            throw new IllegalStateException("只有 FAILED 状态的归档可以重试");
        }
        GisProcessingTask task = taskService.getRequired(archive.getProcessingTaskId());
        GisTileSet tileSet = requireArchivableTileSet(task);
        if (!tileSet.getId().equals(archive.getTileSetId())) {
            throw new IllegalStateException("归档关联的静态瓦片集已发生变化");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = archiveMapper.update(null,
                new LambdaUpdateWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getId, archive.getId())
                        .eq(GisTileSetArchive::getArchiveStatus, TaskStatus.FAILED)
                        .set(GisTileSetArchive::getArchiveStatus, TaskStatus.QUEUED)
                        .set(GisTileSetArchive::getCurrentStage, "QUEUED")
                        .set(GisTileSetArchive::getTotalFileCount, 0L)
                        .set(GisTileSetArchive::getProcessedFileCount, 0L)
                        .set(GisTileSetArchive::getTotalBytes, 0L)
                        .set(GisTileSetArchive::getProcessedBytes, 0L)
                        .set(GisTileSetArchive::getArchiveSizeBytes, 0L)
                        .set(GisTileSetArchive::getStorageKey, null)
                        .set(GisTileSetArchive::getSha256, null)
                        .set(GisTileSetArchive::getErrorMessage, null)
                        .set(GisTileSetArchive::getStartTime, null)
                        .set(GisTileSetArchive::getFinishTime, null)
                        .set(GisTileSetArchive::getUpdateTime, now)
                        .setSql("version = version + 1"));
        if (updated != 1) {
            throw new IllegalStateException("静态瓦片集归档重试状态更新失败");
        }
        eventPublisher.publishEvent(new TileSetArchiveRequestedEvent(archive.getId()));
        log.info("静态瓦片集归档重试已入队: archiveId={}, archiveNo={}, processingTaskId={}, "
                        + "tileSetId={}",
                archive.getId(), archive.getArchiveNo(), archive.getProcessingTaskId(),
                archive.getTileSetId());
        return getArchiveDetail(archive.getArchiveNo());
    }

    public void downloadArchive(String archiveNo, HttpServletRequest request,
            HttpServletResponse response) {
        GisTileSetArchive archive = getRequiredByNo(archiveNo);
        if (archive.getArchiveStatus() != TaskStatus.COMPLETED
                || trimToNull(archive.getStorageKey()) == null) {
            throw new IllegalStateException("静态瓦片集归档尚未完成，不能下载");
        }
        Path archivePath = storageService.resolveArchive(archive.getStorageKey());
        log.info("开始下载静态瓦片集归档: archiveId={}, archiveNo={}, rangeRequest={}",
                archive.getId(), archive.getArchiveNo(), request.getHeader("Range") != null);
        FileDownloadUtil.DownloadResult result = fileDownloadUtil.writePathToResponse(
                archivePath, archive.getArchiveName(), request, response);
        if (result.transferAccepted()) {
            recordDownload(archive.getId());
            log.info("静态瓦片集归档下载已接受: archiveId={}, archiveNo={}",
                    archive.getId(), archive.getArchiveNo());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public GisTileSetArchive claimRunning(Long archiveId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = archiveMapper.update(null,
                new LambdaUpdateWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getId, archiveId)
                        .eq(GisTileSetArchive::getArchiveStatus, TaskStatus.QUEUED)
                        .set(GisTileSetArchive::getArchiveStatus, TaskStatus.RUNNING)
                        .set(GisTileSetArchive::getCurrentStage, "SCANNING")
                        .set(GisTileSetArchive::getStartTime, now)
                        .set(GisTileSetArchive::getUpdateTime, now)
                        .setSql("version = version + 1"));
        if (updated != 1) {
            log.debug("归档任务领取已忽略: archiveId={}, reason=NOT_QUEUED", archiveId);
            return null;
        }
        GisTileSetArchive archive = archiveMapper.selectById(archiveId);
        log.info("归档任务已领取: archiveId={}, archiveNo={}, status=RUNNING, stage=SCANNING",
                archiveId, archive == null ? null : archive.getArchiveNo());
        return archive;
    }

    public void initializeProgress(Long archiveId, long totalFileCount, long totalBytes) {
        if (totalFileCount < 1 || totalBytes < 0) {
            throw new IllegalArgumentException("归档文件统计结果不合法");
        }
        int updated = archiveMapper.update(null,
                new LambdaUpdateWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getId, archiveId)
                        .eq(GisTileSetArchive::getArchiveStatus, TaskStatus.RUNNING)
                        .set(GisTileSetArchive::getCurrentStage, "PACKING")
                        .set(GisTileSetArchive::getTotalFileCount, totalFileCount)
                        .set(GisTileSetArchive::getTotalBytes, totalBytes)
                        .set(GisTileSetArchive::getUpdateTime, LocalDateTime.now())
                        .setSql("version = version + 1"));
        if (updated != 1) {
            throw new IllegalStateException("静态瓦片集归档统计信息更新失败");
        }
        log.info("静态瓦片集归档进入打包阶段: archiveId={}, totalFiles={}, totalBytes={}",
                archiveId, totalFileCount, totalBytes);
    }

    public void updateProgress(Long archiveId, long processedFileCount, long processedBytes) {
        int updated = archiveMapper.update(null,
                new LambdaUpdateWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getId, archiveId)
                        .eq(GisTileSetArchive::getArchiveStatus, TaskStatus.RUNNING)
                        .set(GisTileSetArchive::getProcessedFileCount, processedFileCount)
                        .set(GisTileSetArchive::getProcessedBytes, processedBytes)
                        .set(GisTileSetArchive::getUpdateTime, LocalDateTime.now())
                        .setSql("version = version + 1"));
        log.debug("静态瓦片集归档进度已持久化: archiveId={}, processedFiles={}, "
                        + "processedBytes={}, updated={}",
                archiveId, processedFileCount, processedBytes, updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markCompleted(Long archiveId, String storageKey, long archiveSizeBytes,
            String sha256, long totalFileCount, long totalBytes) {
        LocalDateTime now = LocalDateTime.now();
        int updated = archiveMapper.update(null,
                new LambdaUpdateWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getId, archiveId)
                        .eq(GisTileSetArchive::getArchiveStatus, TaskStatus.RUNNING)
                        .set(GisTileSetArchive::getArchiveStatus, TaskStatus.COMPLETED)
                        .set(GisTileSetArchive::getCurrentStage, "COMPLETED")
                        .set(GisTileSetArchive::getProcessedFileCount, totalFileCount)
                        .set(GisTileSetArchive::getProcessedBytes, totalBytes)
                        .set(GisTileSetArchive::getArchiveSizeBytes, archiveSizeBytes)
                        .set(GisTileSetArchive::getStorageKey, storageKey)
                        .set(GisTileSetArchive::getSha256, sha256)
                        .set(GisTileSetArchive::getFinishTime, now)
                        .set(GisTileSetArchive::getUpdateTime, now)
                        .setSql("version = version + 1"));
        if (updated != 1) {
            throw new IllegalStateException("静态瓦片集归档完成状态更新失败");
        }
        log.info("静态瓦片集归档状态已完成: archiveId={}, archiveSizeBytes={}, "
                        + "totalFiles={}, totalBytes={}, sha256={}",
                archiveId, archiveSizeBytes, totalFileCount, totalBytes, sha256);
    }

    public void markFailed(Long archiveId, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        int updated = archiveMapper.update(null,
                new LambdaUpdateWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getId, archiveId)
                        .in(GisTileSetArchive::getArchiveStatus,
                                TaskStatus.QUEUED, TaskStatus.RUNNING)
                        .set(GisTileSetArchive::getArchiveStatus, TaskStatus.FAILED)
                        .set(GisTileSetArchive::getCurrentStage, "FAILED")
                        .set(GisTileSetArchive::getErrorMessage,
                                abbreviate(errorMessage, 1000))
                        .set(GisTileSetArchive::getFinishTime, now)
                        .set(GisTileSetArchive::getUpdateTime, now)
                        .setSql("version = version + 1"));
        if (updated == 1) {
            log.warn("静态瓦片集归档状态已标记失败: archiveId={}, error={}",
                    archiveId, abbreviate(errorMessage, 1000));
        } else {
            log.warn("静态瓦片集归档失败状态更新已忽略: archiveId={}, reason=NOT_ACTIVE",
                    archiveId);
        }
    }

    public GisTileSetArchive getRequiredEntity(Long archiveId) {
        if (archiveId == null) {
            throw new IllegalArgumentException("静态瓦片集归档ID不能为空");
        }
        GisTileSetArchive archive = archiveMapper.selectById(archiveId);
        if (archive == null) {
            throw new IllegalArgumentException("静态瓦片集归档不存在: " + archiveId);
        }
        return archive;
    }

    private GisTileSet requireArchivableTileSet(GisProcessingTask task) {
        if (task.getTaskStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException("只有 COMPLETED 状态的处理任务可以归档");
        }
        GisTileSet tileSet = tileSetMapper.selectByTaskId(task.getId());
        if (tileSet == null || !"READY".equals(tileSet.getTileSetStatus())) {
            throw new IllegalStateException("处理任务没有 READY 状态的静态瓦片集");
        }
        Path tilesRoot = storageService.resolveTiles(tileSet.getOutputKey());
        if (!Files.isDirectory(tilesRoot)) {
            throw new IllegalStateException("静态瓦片集目录不存在: " + tileSet.getId());
        }
        return tileSet;
    }

    private GisTileSetArchive findByTileSetId(Long tileSetId) {
        return archiveMapper.selectOne(new LambdaQueryWrapper<GisTileSetArchive>()
                .eq(GisTileSetArchive::getTileSetId, tileSetId));
    }

    private GisTileSetArchive getRequiredByNo(String archiveNo) {
        String normalizedArchiveNo = normalizeArchiveNo(archiveNo);
        GisTileSetArchive archive = archiveMapper.selectOne(
                new LambdaQueryWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getArchiveNo, normalizedArchiveNo));
        if (archive == null) {
            throw new IllegalArgumentException("静态瓦片集归档不存在: " + normalizedArchiveNo);
        }
        return archive;
    }

    private void recordDownload(Long archiveId) {
        archiveMapper.update(null,
                new LambdaUpdateWrapper<GisTileSetArchive>()
                        .eq(GisTileSetArchive::getId, archiveId)
                        .eq(GisTileSetArchive::getArchiveStatus, TaskStatus.COMPLETED)
                        .setSql("download_count = download_count + 1")
                        .set(GisTileSetArchive::getLastDownloadTime, LocalDateTime.now())
                        .set(GisTileSetArchive::getUpdateTime, LocalDateTime.now())
                        .setSql("version = version + 1"));
    }

    private String buildArchiveName(GisProcessingTask task) {
        String source = task.getTaskName() + "_" + task.getTaskNo();
        StringBuilder safeName = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            safeName.append(ch < 0x20 || "\\/:*?\"<>|".indexOf(ch) >= 0 ? '_' : ch);
        }
        String baseName = safeName.toString().trim();
        if (baseName.length() > 240) {
            baseName = baseName.substring(0, 240);
        }
        return (baseName.isEmpty() ? task.getTaskNo() : baseName) + ".zip";
    }

    private GisProcessingType normalizeProcessingType(String value) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return GisProcessingType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("处理类型只能为 TERRAIN、IMAGERY 或 VECTOR", ex);
        }
    }

    private TaskStatus normalizeArchiveStatus(String value) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            TaskStatus status = TaskStatus.valueOf(normalized);
            if (status == TaskStatus.PARTIAL_FAILED) {
                throw new IllegalArgumentException("归档状态不支持 PARTIAL_FAILED");
            }
            return status;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "归档状态只能为 QUEUED、RUNNING、COMPLETED 或 FAILED", ex);
        }
    }

    private String normalizeArchiveNo(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || !normalized.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("静态瓦片集归档编号不合法");
        }
        return normalized;
    }

    private String upperTrimToNull(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String abbreviate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value : value.substring(0, maxLength);
    }

    private Long currentOperatorId() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        Object value = attributes.getRequest().getAttribute(CurrentOperator.REQUEST_ATTRIBUTE);
        return value instanceof CurrentOperator operator ? operator.userId() : null;
    }

    /** 事务提交后触发静态瓦片集归档。 */
    public record TileSetArchiveRequestedEvent(Long archiveId) {
    }
}
