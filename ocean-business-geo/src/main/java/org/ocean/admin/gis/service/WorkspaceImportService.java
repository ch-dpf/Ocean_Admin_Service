package org.ocean.admin.gis.service;

import org.ocean.admin.gis.progress.RealtimeProgressService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.annotation.Scheduled;
import org.ocean.admin.gis.progress.ProgressSnapshot;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUpdate;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.admin.gis.dto.TaskStatus;

/** 服务器工作区扫描、登记及解析放行；操作者在提交时显式传入。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceImportService {


    private final GisDictionaryService dictionaries;
    private final GisFileMetaMapper gisFileMetaMapper;
    private final GisDataSetMapper gisDataSetMapper;
    private final DataSetService dataSetService;
    private final FileSpatialMetadataService spatialMetadataService;
    private final VectorDataSetStyleService vectorDataSetStyles;
    private final ControlledWorkspaceResolver workspaceResolver;
    private final GisProcessingProperties processingProperties;
    private final TransactionTemplate transactions;
    private final FileFormatService formats;

    private static final String WORKSPACE_TASK_TYPE = "GIS_WORKSPACE_IMPORT";
    private static final long WORKSPACE_PUSH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private final Map<String, WorkspaceRun> workspaceRuns = new ConcurrentHashMap<>();
    private final Semaphore workspaceSlots = new Semaphore(100);
    private final RealtimeProgressService progressService;

    @Resource(name = "taskExecutor")
    private Executor workspaceExecutor;

    /** 查询配置的工作区根路径，供接入界面说明相对路径的起点。 */
    public GisFileMetaVO.WorkspaceInfo workspaceInfo(String workspaceCode) {
        Path root = workspaceResolver.configuredRoot(workspaceCode);
        return new GisFileMetaVO.WorkspaceInfo(workspaceCode.toUpperCase(Locale.ROOT), root.toString());
    }

    /**
     * 扫描、登记和解析进度在后台完成
     * @param dataSetId 数据集ID
     * @param request 工作区请求
     * @return 工作区提交
     */
    public GisFileMetaVO.WorkspaceSubmission addWorkspaceFiles(Long dataSetId, GisFileMetaVO.WorkspaceRequest request, Long operatorId) {
        if (dataSetId == null || dataSetId < 1) {
            throw new IllegalArgumentException("数据集ID无效");
        }
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除");
        }
        if (request.vectorPlacement() != null) dictionaries.requireItem("vectorPlacement", request.vectorPlacement());
        if (request.vectorPlacement() != null && !Long.valueOf(2).equals(dataSet.getCategoryId())) {
            throw new IllegalArgumentException("仅矢量数据集支持设置矢量归组方式");
        }
        if (Long.valueOf(2).equals(dataSet.getCategoryId()))
            vectorDataSetStyles.validatePlacement(dataSetId, request.vectorPlacement() == null ? "AUTO" : request.vectorPlacement());
        Path entry = workspaceResolver.resolve(request.workspaceCode(), request.relativePath());
        String kind = Files.isDirectory(entry) ? "DIRECTORY" : "FILE";
        if ("FILE".equals(kind)) {
            String reason = formats.checkAcceptance(dataSet.getCategoryId(), entry.getFileName().toString());
            if (reason != null) throw new IllegalArgumentException(reason);
        }
        if (!workspaceSlots.tryAcquire()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "工作区接入任务繁忙，请稍后重试");
        }
        String progressId = TaskNoGenerator.generateWorkspaceImportTaskNo();
        WorkspaceRun run = new WorkspaceRun(progressId, dataSet, request, kind, operatorId);
        workspaceRuns.put(progressId, run);
        try {
            run.progressHandle = progressService.create(progressId, null, "工作区接入：" + dataSet.getDataSetName(),
                    0, WORKSPACE_TASK_TYPE, ProgressUnit.FILE,
                    new ProgressSnapshot.Resource("DATA_SET", dataSetId.toString(), "/api/gis/dataSet/" + dataSetId));
            workspaceExecutor.execute(() -> executeWorkspaceImport(run));
        } catch (RuntimeException ex) {
            synchronized (run) {
                run.error = "工作区接入任务未能启动";
                run.scanComplete = true;
                finishWorkspaceImport(run);
            }
            workspaceRuns.remove(progressId, run);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "工作区接入队列已满，请稍后重试");
        }
        return new GisFileMetaVO.WorkspaceSubmission(dataSetId,
                ProgressSnapshot.reference(progressId, WORKSPACE_TASK_TYPE, TaskStatus.QUEUED));
    }

    private void executeWorkspaceImport(WorkspaceRun run) {
        try {
            reportWorkspaceProgress(run, true);
            Path entry = workspaceResolver.resolve(run.request.workspaceCode(), run.request.relativePath());
            if (Files.isDirectory(entry) != "DIRECTORY".equals(run.entryType))
                throw new IllegalArgumentException("工作区入口类型已变化，请重新提交");
            if ("DIRECTORY".equals(run.entryType)) scanWorkspace(entry, run);
            else {
                countWorkspaceEntry(run);
                registerWorkspaceCandidate(run, entry);
            }
        } catch (RuntimeException ex) {
            synchronized (run) {
                run.error = ex instanceof IllegalArgumentException || ex instanceof IllegalStateException
                        ? ex.getMessage() : "工作区扫描登记失败";
                run.registrationFailed++;
                run.lastError = run.error;
            }
            log.warn("工作区接入停止: progressId={}, dataSetId={}", run.id, run.dataSet.getId(), ex);
        } finally {
            synchronized (run) {
                run.scanComplete = true;
                if (run.registered == 0 && run.duplicates == 0 && run.unsupported == 0
                        && run.registrationFailed == 0 && run.error == null) {
                    run.error = "未发现该数据集分类可接入的文件";
                    run.registrationFailed++;
                }
                reportWorkspaceProgress(run, true);
                releaseWorkspaceMetadata(run);
            }
        }
    }

    private void countWorkspaceEntry(WorkspaceRun run) {
        synchronized (run) {
            if (++run.scanned > processingProperties.getWorkspaceMaxScanEntries())
                throw new IllegalArgumentException("扫描条目超过工作区接入上限，已登记文件保留，请缩小目录范围");
            reportWorkspaceProgress(run, false);
        }
    }

    private void registerWorkspaceCandidate(WorkspaceRun run, Path path) {
        String relative = workspaceResolver.normalizeRelative(path, run.request.workspaceCode());
        synchronized (run) { run.currentPath = relative; }
        GisFileMetaVO.WorkspaceItem item;
        try {
            item = transactions.execute(status -> registerWorkspaceFile(run.dataSet, run.request, relative, run.operatorId, run.id));
        } catch (RuntimeException ex) {
            workspaceRegistrationFailed(run, "文件登记失败：" + relative);
            log.warn("工作区文件登记失败: progressId={}, relativePath={}", run.id, relative, ex);
            return;
        }
        synchronized (run) {
            if ("DUPLICATE".equals(item.status())) run.duplicates++;
            else {
                run.registered++;
            }
            reportWorkspaceProgress(run, false);
        }
    }

    private void releaseWorkspaceMetadata(WorkspaceRun run) {
        if (run.metadataReleased) return;
        try {
            spatialMetadataService.releaseIngestion(run.id);
            progressService.refreshIngestion(run.id);
            run.metadataReleased = true;
            finishWorkspaceImport(run);
        } catch (RuntimeException ex) {
            run.lastError = "解析放行暂未完成";
            log.warn("工作区解析放行失败: progressId={}", run.id, ex);
        }
    }

    private void workspaceRegistrationFailed(WorkspaceRun run, String message) {
        synchronized (run) {
            run.registrationFailed++;
            run.lastError = message;
            reportWorkspaceProgress(run, false);
        }
    }

    private void reportWorkspaceProgress(WorkspaceRun run, boolean force) {
        synchronized (run) {
            if (run.completedAt != null || run.progressHandle == null) return;
            run.progressDirty = true;
            long now = System.nanoTime();
            if (!force && now - run.lastPush < WORKSPACE_PUSH_INTERVAL_NANOS) return;
            run.lastPush = now;
            String message = "已扫描" + run.scanned + "项，登记" + run.registered + "个，重复"
                    + run.duplicates + "个，不支持" + run.unsupported + "个，登记失败" + run.registrationFailed + "个";
            if (run.currentPath != null) message += "；当前：" + run.currentPath;
            if (run.lastError != null) message += "；" + run.lastError;
            var stage = !run.scanComplete
                    ? ProgressStage.counting("SCANNING", message, run.scanned,
                            ProgressUnit.ENTRY)
                    : run.registered == 0 ? ProgressStage.indeterminate("EXTRACTING_METADATA", message)
                    : ProgressStage.determinate("EXTRACTING_METADATA", message, 0, run.registered, ProgressUnit.FILE);
            List<ProgressUpdate> updates = new ArrayList<>();
            updates.add(ProgressUpdate.summaryChanged(new org.ocean.admin.gis.progress.ProgressSummary(
                    run.registered + run.registrationFailed + run.duplicates + run.unsupported,
                    0, run.registrationFailed,
                    run.duplicates + run.unsupported, ProgressUnit.FILE)));
            updates.add(ProgressUpdate.metric("SCANNED_ENTRIES", "已扫描条目", run.scanned, null, ProgressUnit.ENTRY));
            updates.add(ProgressUpdate.metric("REGISTERED_FILES", "已登记文件", run.registered, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("DUPLICATE_FILES", "重复跳过", run.duplicates, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("UNSUPPORTED_FILES", "格式不支持", run.unsupported, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("REGISTRATION_FAILED", "登记失败", run.registrationFailed, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.stageChanged(stage));
            updates.add(ProgressUpdate.overallProgressChanged(null));
            progressService.update(run.progressHandle, updates.toArray(ProgressUpdate[]::new));
            run.progressDirty = false;
        }
    }

    /** 扫描执行结束即可释放扫描槽；解析结果由统一进度服务跨实例汇总。 */
    private void finishWorkspaceImport(WorkspaceRun run) {
        if (run.completedAt != null) return;
        run.completedAt = LocalDateTime.now();
        workspaceRuns.remove(run.id, run);
        workspaceSlots.release();
        if (run.progressHandle != null && !run.metadataReleased)
            progressService.finish(run.progressHandle, TaskStatus.FAILED, run.error == null ? "工作区接入中断" : run.error);
    }

    /** 节流窗口内的最后一次变化也会发送，避免只有少量慢文件时中间计数长期不更新。 */
    @Scheduled(fixedDelay = 500)
    public void flushWorkspaceProgress() {
        for (WorkspaceRun run : workspaceRuns.values()) {
            synchronized (run) {
                if (run.completedAt != null) continue;
                long now = System.nanoTime();
                if (now - run.waitRenewedAt >= TimeUnit.SECONDS.toNanos(30)) {
                    run.waitRenewedAt = now;
                    try {
                        if (!run.scanComplete) spatialMetadataService.renewIngestionWaiting(run.id);
                        else if (!run.metadataReleased) releaseWorkspaceMetadata(run);
                    } catch (RuntimeException ex) {
                        log.warn("工作区扫描等待续租失败: progressId={}", run.id, ex);
                    }
                }
                if (run.progressDirty) reportWorkspaceProgress(run, false);
            }
        }
    }

    /** 本次接入的运行态关联；不进入gis_processing_task，也不充当文件身份。 */
    private static final class WorkspaceRun {
        final String id;
        final GisDataSet dataSet;
        final GisFileMetaVO.WorkspaceRequest request;
        final String entryType;
        final Long operatorId;
        long scanned, registered, duplicates, unsupported, registrationFailed, lastPush;
        boolean scanComplete;
        boolean metadataReleased;
        long waitRenewedAt = System.nanoTime();
        volatile boolean progressDirty;
        String currentPath, lastError, error;
        volatile LocalDateTime completedAt;
        ProgressSnapshot.Handle progressHandle;

        WorkspaceRun(String id, GisDataSet dataSet, GisFileMetaVO.WorkspaceRequest request, String entryType, Long operatorId) {
            this.id = id; this.dataSet = dataSet; this.request = request; this.entryType = entryType; this.operatorId = operatorId;
        }
    }

    /** 边遍历边登记，不收集目录路径或文件清单，文件结果通过进度标识回报。 */
    private void scanWorkspace(Path entry, WorkspaceRun run) {
        try {
            Files.walkFileTree(entry, Set.<FileVisitOption>of(),
                    Boolean.TRUE.equals(run.request.includeSubdirectories()) ? Integer.MAX_VALUE : 1,
                    new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                            countWorkspaceEntry(run);
                            try {
                                workspaceResolver.resolve(run.request.workspaceCode(),
                                        workspaceResolver.normalizeRelative(directory, run.request.workspaceCode()));
                                return FileVisitResult.CONTINUE;
                            } catch (IllegalArgumentException ex) {
                                workspaceRegistrationFailed(run, "目录不在受控范围或不可读");
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }

                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            countWorkspaceEntry(run);
                            if (attributes.isDirectory()) return FileVisitResult.CONTINUE;
                            String reason = formats.checkAcceptance(run.dataSet.getCategoryId(), file.getFileName().toString());
                            if (reason != null) {
                                synchronized (run) {
                                    run.unsupported++;
                                    run.lastError = reason;
                                    reportWorkspaceProgress(run, false);
                                }
                            } else if (attributes.isRegularFile()) registerWorkspaceCandidate(run, file);
                            else workspaceRegistrationFailed(run, "禁止接入链接或特殊文件");
                            return FileVisitResult.CONTINUE;
                        }

                        @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                            countWorkspaceEntry(run);
                            workspaceRegistrationFailed(run, "无法扫描文件或目录");
                            return FileVisitResult.CONTINUE;
                        }

                        @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                            if (error != null) workspaceRegistrationFailed(run, "目录未能完整扫描");
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ex) {
            throw new IllegalArgumentException("工作区目录扫描失败");
        }
    }

    private GisFileMetaVO.WorkspaceItem registerWorkspaceFile(GisDataSet expectedDataSet,
            GisFileMetaVO.WorkspaceRequest request, String relative, Long operatorId, String progressId) {
        GisDataSet locked = gisDataSetMapper.selectActiveByIdForUpdate(expectedDataSet.getId());
        if (locked == null || !Objects.equals(locked.getCategoryId(), expectedDataSet.getCategoryId()))
            throw new IllegalStateException("数据集已删除或分类已变更，请重新接入");
        Path path = workspaceResolver.resolve(request.workspaceCode(), relative);
        BasicFileAttributes attributes = workspaceResolver.fileAttributes(path);
        String normalized = workspaceResolver.normalizeRelative(path, request.workspaceCode());
        String code = request.workspaceCode().toUpperCase(Locale.ROOT);
        String reason = formats.checkAcceptance(locked.getCategoryId(), path.getFileName().toString());
        if (reason != null) throw new IllegalArgumentException(reason);
        GisFileMeta existing = gisFileMetaMapper.selectOne(new LambdaQueryWrapper<GisFileMeta>()
                .eq(GisFileMeta::getDataSetId, locked.getId()).eq(GisFileMeta::getSourceType, "WORKSPACE")
                .eq(GisFileMeta::getWorkspaceCode, code).eq(GisFileMeta::getRelativePath, normalized));
        if (existing != null) {
            boolean changed = !Objects.equals(existing.getSizeBytes(), attributes.size())
                    || !Objects.equals(existing.getSourceModifiedAt(), attributes.lastModifiedTime().toMillis());
            if (changed) spatialMetadataService.markSourceUnavailable(existing.getId(), "SOURCE_CHANGED", "工作区源文件已变化，请删除记录后重新接入");
            return new GisFileMetaVO.WorkspaceItem(existing.getId(), normalized, "DUPLICATE",
                    changed ? "源文件已变化，请删除记录后重新接入" : null);
        }
        if (normalized.length() > 1000 || path.getFileName().toString().length() > 255)
            throw new IllegalArgumentException("文件路径或名称过长");
        GisFileMeta meta = new GisFileMeta();
        meta.setId(IdWorker.getId());
        meta.setDataSetId(locked.getId());
        meta.setIngestProgressId(progressId);
        meta.setSourceType("WORKSPACE");
        meta.setWorkspaceCode(code);
        meta.setRelativePath(normalized);
        meta.setSourceModifiedAt(attributes.lastModifiedTime().toMillis());
        meta.setOriginalName(path.getFileName().toString());
        meta.setExtension(FileUploadUtil.extensionOf(meta.getOriginalName()));
        meta.setSizeBytes(attributes.size());
        meta.setCreatedBy(operatorId);
        meta.setFileStatus("READY");
        meta.setCleanupStatus("NOT_REQUIRED");
        meta.setMetadataStatus("WAITING");
        meta.setMetadataLeaseExpiresAt(LocalDateTime.now().plusMinutes(15));
        meta.setMetadataVersion(0L);
        meta.setDeleted(0);
        meta.setCreateTime(LocalDateTime.now());
        meta.setUpdateTime(meta.getCreateTime());
        if (Long.valueOf(2).equals(locked.getCategoryId())) {
            meta.setVectorDatasetStyleId(vectorDataSetStyles.preparePlacement(locked.getId(), request.vectorPlacement() == null ? "AUTO" : request.vectorPlacement()).getId());
            meta.setVectorPlacement(request.vectorPlacement() == null ? "AUTO" : request.vectorPlacement());
        }
        if (gisFileMetaMapper.insert(meta) != 1) throw new IllegalStateException("工作区文件登记失败");
        dataSetService.updateFileStatistics(null, meta);
        return new GisFileMetaVO.WorkspaceItem(meta.getId(), normalized, "WAITING", null);
    }
}
