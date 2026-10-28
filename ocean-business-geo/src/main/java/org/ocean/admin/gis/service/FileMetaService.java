package org.ocean.admin.gis.service;

import org.ocean.admin.gis.progress.RealtimeProgressService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileTransferRecordItem;
import org.ocean.admin.gis.dto.GisFileQuery;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileTransferRecordItemMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.util.FileDownloadUtil;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.ocean.admin.gis.progress.ProgressSnapshot;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUpdate;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.admin.gis.dto.TaskStatus;

/** GIS 文件元数据服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileMetaService {

    private static final Set<String> STORAGE_TYPES = Set.of("LOCAL", "MINIO", "S3");
    private static final Set<String> FILE_STATUSES = Set.of("PENDING", "READY", "FAILED");

    private final GisFileMetaMapper gisFileMetaMapper;
    private final GisFileTransferRecordItemMapper gisFileTransferRecordItemMapper;
    private final GisProcessingInputMapper processingInputMapper;
    private final GisDataSetMapper gisDataSetMapper;
    private final DataSetService dataSetService;
    private final FileTransferRecordService fileTransferRecordService;
    private final FileDownloadUtil fileDownloadUtil;
    private final FileUploadUtil fileUploadUtil;
    private final FileSpatialMetadataService spatialMetadataService;
    private final GisVectorMapper vectorMapper;
    private final VectorDataSetStyleService vectorDataSetStyles;
    private final ControlledWorkspaceResolver workspaceResolver;
    private final GisProcessingProperties processingProperties;
    private final TransactionTemplate transactions;

    private static final String WORKSPACE_TASK_TYPE = "GIS_WORKSPACE_IMPORT";
    private static final long WORKSPACE_PUSH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private final Map<String, WorkspaceRun> workspaceRuns = new ConcurrentHashMap<>();
    private final Map<Long, WorkspaceRun> workspaceFileOwners = new ConcurrentHashMap<>();
    private final Semaphore workspaceSlots = new Semaphore(100);
    private final RealtimeProgressService progressService;
    private final ApplicationEventPublisher events;

    @Resource(name = "taskExecutor")
    private Executor workspaceExecutor;

    /** 请求线程只校验入口；扫描、登记和解析进度在后台完成，不创建任何业务任务表记录。 */
    public GisFileMetaVO.WorkspaceSubmission addWorkspaceFiles(Long dataSetId, GisFileMetaVO.WorkspaceRequest request) {
        if (dataSetId == null || dataSetId < 1) throw new IllegalArgumentException("数据集ID无效");
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) throw new IllegalArgumentException("数据集不存在或已删除");
        if (request.vectorPlacement() != null && !Set.of("AUTO", "UNKNOWN").contains(request.vectorPlacement()))
            throw new IllegalArgumentException("矢量归组方式须为AUTO或UNKNOWN");
        if (request.vectorPlacement() != null && !Long.valueOf(2).equals(dataSet.getCategoryId()))
            throw new IllegalArgumentException("仅矢量数据集支持设置矢量归组方式");
        Path entry = workspaceResolver.resolve(request.workspaceCode(), request.relativePath());
        String kind = Files.isDirectory(entry) ? "DIRECTORY" : "FILE";
        if ("FILE".equals(kind) && !supportsWorkspaceFile(dataSet.getCategoryId(), entry))
            throw new IllegalArgumentException("文件格式不受该数据集分类的空间解析器支持");
        if (!workspaceSlots.tryAcquire()) throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "工作区接入任务繁忙，请稍后重试");
        String progressId = TaskNoGenerator.generateWorkspaceImportTaskNo();
        WorkspaceRun run = new WorkspaceRun(progressId, dataSet, request, kind, currentOperatorId());
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
            }
            log.warn("工作区接入停止: progressId={}, dataSetId={}", run.id, run.dataSet.getId(), ex);
        } finally {
            synchronized (run) {
                run.scanComplete = true;
                if (run.registered == 0 && run.duplicates == 0 && run.registrationFailed == 0 && run.error == null)
                    run.error = "未发现该数据集分类可接入的文件";
                reportWorkspaceProgress(run, true);
                finishWorkspaceImport(run);
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
            item = transactions.execute(status -> registerWorkspaceFile(run.dataSet, run.request, relative, run.operatorId));
        } catch (RuntimeException ex) {
            workspaceRegistrationFailed(run, "文件登记失败：" + relative);
            log.warn("工作区文件登记失败: progressId={}, relativePath={}", run.id, relative, ex);
            return;
        }
        synchronized (run) {
            if ("DUPLICATE".equals(item.status())) run.duplicates++;
            else {
                run.registered++;
                run.pending.put(item.fileMetaId(), item.relativePath());
                workspaceFileOwners.put(item.fileMetaId(), run);
            }
            reportWorkspaceProgress(run, false);
        }
        if ("QUEUED".equals(item.status())) {
            // 关闭“元数据已完成，但运行态文件关联尚未建立”的通知竞态窗口。
            try {
                GisFileMeta file = gisFileMetaMapper.selectById(item.fileMetaId());
                if (file == null || "FAILED".equals(file.getMetadataStatus())) completeWorkspaceFile(item.fileMetaId(), false);
                else if (Set.of("COMPLETED", "NOT_APPLICABLE").contains(file.getMetadataStatus()))
                    completeWorkspaceFile(item.fileMetaId(), true);
            } catch (RuntimeException ex) {
                synchronized (run) { run.error = "登记后的文件状态检查失败，请查询文件元数据"; }
                completeWorkspaceFile(item.fileMetaId(), false);
            }
        }
    }

    private void workspaceRegistrationFailed(WorkspaceRun run, String message) {
        synchronized (run) {
            run.registrationFailed++;
            run.lastError = message;
            reportWorkspaceProgress(run, false);
        }
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkspaceMetadataProgress(FileSpatialMetadataService.MetadataProgress event) {
        for (Long fileId : event.fileIds()) {
            if (event.succeeded() != null) completeWorkspaceFile(fileId, event.succeeded());
            else {
                WorkspaceRun run = workspaceFileOwners.get(fileId);
                if (run != null) synchronized (run) {
                    run.currentPath = run.pending.getOrDefault(fileId, run.currentPath);
                    reportWorkspaceProgress(run, false);
                }
            }
        }
    }

    private void completeWorkspaceFile(Long fileId, boolean succeeded) {
        WorkspaceRun run = workspaceFileOwners.remove(fileId);
        if (run == null) return;
        synchronized (run) {
            if (run.pending.remove(fileId) == null || run.completedAt != null) return;
            if (succeeded) run.succeeded++;
            else run.metadataFailed++;
            reportWorkspaceProgress(run, false);
            finishWorkspaceImport(run);
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
                    : run.registered == 0
                        ? ProgressStage.indeterminate("EXTRACTING_METADATA", message)
                        : ProgressStage.determinate("EXTRACTING_METADATA", message,
                                run.succeeded + run.metadataFailed, run.registered,
                                ProgressUnit.FILE);
            List<ProgressUpdate> updates = new ArrayList<>();
            updates.add(ProgressUpdate.summaryChanged(new org.ocean.admin.gis.progress.ProgressSummary(
                    run.registered + run.registrationFailed + run.duplicates + run.unsupported,
                    run.succeeded, run.registrationFailed + run.metadataFailed,
                    run.duplicates + run.unsupported, ProgressUnit.FILE)));
            updates.add(ProgressUpdate.metric("SCANNED_ENTRIES", "已扫描条目", run.scanned, null, ProgressUnit.ENTRY));
            updates.add(ProgressUpdate.metric("REGISTERED_FILES", "已登记文件", run.registered, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("DUPLICATE_FILES", "重复跳过", run.duplicates, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("UNSUPPORTED_FILES", "格式不支持", run.unsupported, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("REGISTRATION_FAILED", "登记失败", run.registrationFailed, null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("METADATA_SUCCEEDED", "元数据成功", run.succeeded,
                    run.scanComplete ? run.registered : null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.metric("METADATA_FAILED", "元数据失败", run.metadataFailed,
                    run.scanComplete ? run.registered : null, ProgressUnit.FILE));
            updates.add(ProgressUpdate.stageChanged(stage));
            if (run.scanComplete) {
                long total = run.registered + run.registrationFailed;
                int percent = total == 0 ? 100 : (int)((run.succeeded + run.metadataFailed + run.registrationFailed) * 100 / total);
                updates.add(ProgressUpdate.overallProgressChanged(percent));
            }
            progressService.update(run.progressHandle, updates.toArray(ProgressUpdate[]::new));
            run.progressDirty = false;
        }
    }

    /** 扫描结束且所有新登记文件进入元数据终态，才结束本次接入进度。调用方持有run锁。 */
    private void finishWorkspaceImport(WorkspaceRun run) {
        if (!run.scanComplete || !run.pending.isEmpty() || run.completedAt != null) return;
        reportWorkspaceProgress(run, true);
        String message = "接入结束：登记" + run.registered + "个，解析成功" + run.succeeded
                + "个，解析失败" + run.metadataFailed + "个，登记失败" + run.registrationFailed
                + "个，重复跳过" + run.duplicates + "个，不支持跳过" + run.unsupported + "个";
        long failed = run.registrationFailed + run.metadataFailed;
        TaskStatus status = run.error != null ? TaskStatus.FAILED
                : failed == 0 ? TaskStatus.COMPLETED
                : run.succeeded == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL_FAILED;
        if (run.progressHandle != null)
            progressService.finish(run.progressHandle, status, run.error == null ? message : run.error + "；" + message);
        run.completedAt = LocalDateTime.now();
        workspaceSlots.release();
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupWorkspaceProgress() {
        LocalDateTime expiry = LocalDateTime.now().minusMinutes(30);
        workspaceRuns.entrySet().removeIf(entry -> entry.getValue().completedAt != null
                && entry.getValue().completedAt.isBefore(expiry));
    }

    /** 节流窗口内的最后一次变化也会发送，避免只有少量慢文件时中间计数长期不更新。 */
    @Scheduled(fixedDelay = 500)
    public void flushWorkspaceProgress() {
        for (WorkspaceRun run : workspaceRuns.values()) {
            if (run.progressDirty && run.completedAt == null) reportWorkspaceProgress(run, false);
        }
    }

    /** 本次接入的运行态关联；不进入gis_processing_task，也不充当文件身份。 */
    private static final class WorkspaceRun {
        final String id;
        final GisDataSet dataSet;
        final GisFileMetaVO.WorkspaceRequest request;
        final String entryType;
        final Long operatorId;
        final Map<Long, String> pending = new HashMap<>();
        long scanned, registered, duplicates, unsupported, registrationFailed, succeeded, metadataFailed, lastPush;
        boolean scanComplete;
        volatile boolean progressDirty;
        String currentPath, lastError, error;
        volatile LocalDateTime completedAt;
        ProgressSnapshot.Handle progressHandle;

        WorkspaceRun(String id, GisDataSet dataSet, GisFileMetaVO.WorkspaceRequest request, String entryType, Long operatorId) {
            this.id = id; this.dataSet = dataSet; this.request = request; this.entryType = entryType; this.operatorId = operatorId;
        }
    }

    private boolean supportsWorkspaceFile(Long categoryId, Path path) {
        try {
            String extension = FileUploadUtil.extensionOf(path.getFileName().toString());
            return switch (categoryId.intValue()) {
                case 0, 1 -> Set.of("tif", "tiff").contains(extension);
                case 2 -> Set.of("geojson", "json").contains(extension);
                default -> throw new IllegalArgumentException("未知GIS数据类别");
            };
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** 边遍历边登记，内存只保留尚未完成解析的文件ID，不收集整个目录的路径或响应明细。 */
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
                            if (!supportsWorkspaceFile(run.dataSet.getCategoryId(), file)) {
                                synchronized (run) { run.unsupported++; reportWorkspaceProgress(run, false); }
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
            GisFileMetaVO.WorkspaceRequest request, String relative, Long operatorId) {
        GisDataSet locked = gisDataSetMapper.selectActiveByIdForUpdate(expectedDataSet.getId());
        if (locked == null || !Objects.equals(locked.getCategoryId(), expectedDataSet.getCategoryId()))
            throw new IllegalStateException("数据集已删除或分类已变更，请重新接入");
        Path path = workspaceResolver.resolve(request.workspaceCode(), relative);
        BasicFileAttributes attributes = workspaceResolver.fileAttributes(path);
        String normalized = workspaceResolver.normalizeRelative(path, request.workspaceCode());
        String code = request.workspaceCode().toUpperCase(Locale.ROOT);
        GisFileMeta existing = gisFileMetaMapper.selectOne(new LambdaQueryWrapper<GisFileMeta>()
                .eq(GisFileMeta::getDataSetId, locked.getId()).eq(GisFileMeta::getSourceType, "WORKSPACE")
                .eq(GisFileMeta::getWorkspaceCode, code).eq(GisFileMeta::getRelativePath, normalized));
        if (existing != null) {
            boolean changed = !Objects.equals(existing.getSizeBytes(), attributes.size())
                    || !Objects.equals(existing.getSourceModifiedAt(), attributes.lastModifiedTime().toMillis());
            if (changed) spatialMetadataService.markSourceUnavailable(existing.getId(), "SOURCE_CHANGED", "工作区源文件已变化，请重新提取空间元信息");
            return new GisFileMetaVO.WorkspaceItem(existing.getId(), normalized, "DUPLICATE",
                    changed ? "源文件已变化，原空间元信息已失效，请重试提取" : null);
        }
        if (!supportsWorkspaceFile(locked.getCategoryId(), path)) throw new IllegalArgumentException("文件格式与数据集分类不匹配");
        if (normalized.length() > 1000 || path.getFileName().toString().length() > 255)
            throw new IllegalArgumentException("文件路径或名称过长");
        GisFileMeta meta = new GisFileMeta();
        meta.setId(IdWorker.getId());
        meta.setDataSetId(locked.getId());
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
        meta.setMetadataStatus("QUEUED");
        meta.setMetadataVersion(0L);
        meta.setDeleted(0);
        meta.setCreateTime(LocalDateTime.now());
        meta.setUpdateTime(meta.getCreateTime());
        if (Long.valueOf(2).equals(locked.getCategoryId())) {
            meta.setVectorDatasetStyleId(vectorDataSetStyles.require(locked.getId(), false).getId());
            meta.setVectorPlacement(request.vectorPlacement() == null ? "AUTO" : request.vectorPlacement());
        }
        if (gisFileMetaMapper.insert(meta) != 1) throw new IllegalStateException("工作区文件登记失败");
        dataSetService.updateFileStatistics(null, meta);
        return new GisFileMetaVO.WorkspaceItem(meta.getId(), normalized, "QUEUED", null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<List<GisFileMetaVO>> getFileMetaPage(GisFileQuery query) {
        query.validate();
        Page<GisFileMetaVO> page = gisFileMetaMapper.selectFilePage(
                new Page<>(query.getCurrent(), query.getSize()), query);
        spatialMetadataService.populate(page.getRecords(), query.isIncludeGeometry());
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public void retryMetadata(Long id) {
        GisFileMeta meta = getRequiredForUpdate(id);
        if (!"READY".equals(meta.getFileStatus())) throw new IllegalArgumentException("仅能重新提取已就绪的源文件");
        if (!"FAILED".equals(meta.getMetadataStatus())) throw new IllegalStateException("仅提取失败的文件可以重试");
        if ("WORKSPACE".equals(meta.getSourceType())) {
            Path source = workspaceResolver.resolve(meta.getWorkspaceCode(), meta.getRelativePath());
            BasicFileAttributes attributes = workspaceResolver.fileAttributes(source);
            if (!Objects.equals(meta.getSizeBytes(), attributes.size())
                    || !Objects.equals(meta.getSourceModifiedAt(), attributes.lastModifiedTime().toMillis())) {
                ensureVectorSourceUnreferenced(id);
                GisFileMeta refreshed = new GisFileMeta();
                refreshed.setId(id);
                refreshed.setSizeBytes(attributes.size());
                refreshed.setSourceModifiedAt(attributes.lastModifiedTime().toMillis());
                gisFileMetaMapper.update(null, new LambdaUpdateWrapper<GisFileMeta>()
                        .eq(GisFileMeta::getId, id).set(GisFileMeta::getSizeBytes, refreshed.getSizeBytes())
                        .set(GisFileMeta::getSourceModifiedAt, refreshed.getSourceModifiedAt())
                        .set(GisFileMeta::getSha256, null));
                refreshed.setDataSetId(meta.getDataSetId());
                refreshed.setFileStatus(meta.getFileStatus());
                refreshed.setDeleted(meta.getDeleted());
                dataSetService.updateFileStatistics(meta, refreshed);
            }
        } else fileTransferRecordService.reopenMetadataImport(id);
        spatialMetadataService.invalidate(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisFileMetaVO createFileMeta(GisFileMetaVO reqVO) {
        validateRequest(reqVO, false);
        if (reqVO.getSourceType() != null && !"UPLOAD".equals(reqVO.getSourceType()))
            throw new IllegalArgumentException("工作区文件请通过数据集工作区接入接口登记");
        ensureDataSetExists(reqVO.getDataSetId());

        String storageType = normalizeUpper(reqVO.getStorageType());
        if (storageType == null) {
            storageType = "LOCAL";
        }
        String storageKey = reqVO.getStorageKey().trim();
        ensureStorageKeyAvailable(storageType, storageKey, null);

        LocalDateTime now = LocalDateTime.now();
        GisFileMeta meta = new GisFileMeta();
        applyEditableFields(meta, reqVO, storageType, storageKey);
        meta.setFileStatus(defaultStatus(reqVO.getFileStatus()));
        meta.setCleanupStatus("NOT_REQUIRED");
        meta.setMetadataStatus("QUEUED");
        meta.setMetadataVersion(0L);
        if (Long.valueOf(2).equals(gisDataSetMapper.selectById(meta.getDataSetId()).getCategoryId())) {
            var snapshot = vectorDataSetStyles.require(meta.getDataSetId(), false);
            meta.setVectorDatasetStyleId(snapshot.getId());
            meta.setVectorPlacement(reqVO.getVectorPlacement() == null ? "AUTO" : reqVO.getVectorPlacement());
        }
        meta.setCreateTime(now);
        meta.setUpdateTime(now);
        meta.setDeleted(0);
        if (gisFileMetaMapper.insert(meta) != 1) {
            throw new IllegalStateException("文件元数据创建失败");
        }
        dataSetService.updateFileStatistics(null, meta);
        log.info("创建 GIS 文件元数据成功: id={}, dataSetId={}, storageKey={}",
                meta.getId(), meta.getDataSetId(), meta.getStorageKey());
        return toVOWithCategory(meta);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisFileMetaVO updateFileMeta(GisFileMetaVO reqVO) {
        validateRequest(reqVO, true);
        GisFileMeta existing = getRequiredForUpdate(reqVO.getId());
        if ("WORKSPACE".equals(existing.getSourceType()))
            throw new IllegalArgumentException("工作区来源信息由服务器维护，调整来源请删除记录后重新接入");
        ensureMutable(existing);
        if ("READY".equals(existing.getFileStatus())
                && !existing.getDataSetId().equals(reqVO.getDataSetId())) {
            throw new IllegalArgumentException("已上传成功的文件不允许变更所属数据集");
        }
        if (existing.getVectorDatasetStyleId() != null && !existing.getDataSetId().equals(reqVO.getDataSetId())) {
            throw new IllegalArgumentException("已固定矢量样式的文件不允许变更所属数据集");
        }
        ensureDataSetExists(reqVO.getDataSetId());

        String storageType = normalizeUpper(reqVO.getStorageType());
        if (storageType == null) {
            storageType = "LOCAL";
        }
        String storageKey = reqVO.getStorageKey().trim();
        ensureStorageKeyAvailable(storageType, storageKey, reqVO.getId());

        GisFileMeta updatedMeta = new GisFileMeta();
        updatedMeta.setId(existing.getId());
        updatedMeta.setVectorDatasetStyleId(existing.getVectorDatasetStyleId());
        updatedMeta.setVectorPlacement(existing.getVectorPlacement());
        if (reqVO.getVectorPlacement() != null && !Objects.equals(reqVO.getVectorPlacement(), existing.getVectorPlacement()))
            throw new IllegalArgumentException("已建立文件的归组方式不可变更");
        applyEditableFields(updatedMeta, reqVO, storageType, storageKey);
        updatedMeta.setCreatedBy(existing.getCreatedBy());
        updatedMeta.setCleanupStatus(existing.getCleanupStatus());
        String requestedStatus = normalizeUpper(reqVO.getFileStatus());
        updatedMeta.setFileStatus(requestedStatus == null ? existing.getFileStatus() : requestedStatus);
        updatedMeta.setUpdateTime(LocalDateTime.now());
        int updated = gisFileMetaMapper.update(null,
                new LambdaUpdateWrapper<GisFileMeta>()
                        .eq(GisFileMeta::getId, existing.getId())
                        .set(GisFileMeta::getDataSetId, updatedMeta.getDataSetId())
                        .set(GisFileMeta::getOriginalName, updatedMeta.getOriginalName())
                        .set(GisFileMeta::getStorageName, updatedMeta.getStorageName())
                        .set(GisFileMeta::getStorageKey, updatedMeta.getStorageKey())
                        .set(GisFileMeta::getStorageType, updatedMeta.getStorageType())
                        .set(GisFileMeta::getExtension, updatedMeta.getExtension())
                        .set(GisFileMeta::getSizeBytes, updatedMeta.getSizeBytes())
                        .set(GisFileMeta::getSha256, updatedMeta.getSha256())
                        .set(GisFileMeta::getCreatedBy, updatedMeta.getCreatedBy())
                        .set(GisFileMeta::getFileStatus, updatedMeta.getFileStatus())
                        .set(GisFileMeta::getErrorMessage, updatedMeta.getErrorMessage())
                        .set(GisFileMeta::getUpdateTime, updatedMeta.getUpdateTime()));
        if (updated != 1) {
            throw new IllegalStateException("文件元数据更新失败: " + reqVO.getId());
        }
        updatedMeta.setDeleted(existing.getDeleted());
        dataSetService.updateFileStatistics(existing, updatedMeta);
        boolean sourceChanged = !Objects.equals(existing.getStorageKey(), updatedMeta.getStorageKey())
                || !Objects.equals(existing.getStorageType(), updatedMeta.getStorageType())
                || !Objects.equals(existing.getSha256(), updatedMeta.getSha256())
                || !Objects.equals(existing.getExtension(), updatedMeta.getExtension())
                || !Objects.equals(existing.getSizeBytes(), updatedMeta.getSizeBytes())
                || !Objects.equals(existing.getFileStatus(), updatedMeta.getFileStatus());
        if (sourceChanged) {
            ensureVectorSourceUnreferenced(existing.getId());
            spatialMetadataService.invalidate(existing.getId());
        }
        updatedMeta.setCreateTime(existing.getCreateTime());
        log.info("更新 GIS 文件元数据成功: id={}", reqVO.getId());
        return toVOWithCategory(getRequired(existing.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFileMeta(Long id) {
        GisFileMeta existing = getRequiredForUpdate(id);
        ensureMutable(existing);
        ensureVectorSourceUnreferenced(id);
        ensureNoActiveProcessingTask(id);
        validatePermanentDeleteStorage(existing);
        String storageKey = trimToNull(existing.getStorageKey());
        if ("UPLOAD".equals(existing.getSourceType()) && storageKey != null) {
            fileUploadUtil.stageStoredForTransactionalDeletion(List.of(storageKey));
        }
        processingInputMapper.detachTerminalByFileMetaId(id);
        gisFileTransferRecordItemMapper.clearFileMetaReference(id);
        if (gisFileMetaMapper.physicallyDeleteActiveById(id) != 1) {
            throw new IllegalStateException("文件物理删除失败: " + id);
        }
        dataSetService.updateFileStatistics(existing, null);
        fileUploadUtil.deleteEmptyDataSetDirectory(existing.getDataSetId());
        events.publishEvent(new FileSpatialMetadataService.MetadataProgress(List.of(id), false));
        log.info("永久删除 GIS 文件成功: id={}, storageKey={}", id, storageKey);
    }

    private void ensureNoActiveProcessingTask(Long id) {
        List<GisProcessingInputMapper.ActiveTaskReference> active =
                processingInputMapper.selectActiveByFileMetaId(id);
        if (!active.isEmpty()) {
            var task = active.getFirst();
            throw new IllegalStateException("文件正在被处理任务使用，请等待任务结束后重试: "
                    + task.taskName() + " (" + task.taskNo() + ", " + task.taskStatus() + ")");
        }
    }

    private void ensureVectorSourceUnreferenced(Long id) {
        if (vectorMapper.countSourceReferences(id) > 0) {
            throw new IllegalStateException("文件仍被矢量图层或导入任务引用，请先删除相关图层并等待导入结束");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisFileMetaVO getFileMetaDetail(Long id) {
        return toVOWithCategory(getRequired(id));
    }

    /**
     * 下载已入库文件的原始内容，支持单区间 Range 与 HEAD 请求。
     *
     * <p>文件范围优先交给 Tomcat sendfile，连接器不支持时由应用流式写出。
     * 下载记录表示传输已交付；登记失败只记错误日志，不中断已经开始的传输。</p>
     */
    public void downloadFile(
            Long id,
            HttpServletRequest request,
            HttpServletResponse response) {
        GisFileMeta meta = getRequired(id);
        ensureDownloadable(meta);
        Path source = resolveSourcePath(meta);
        FileDownloadUtil.DownloadResult result = fileDownloadUtil.writePathToResponse(
                source, meta.getOriginalName(), request, response);
        if (result.transferAccepted()) {
            try {
                fileTransferRecordService.createDownloadRecord(meta, currentOperatorId());
            } catch (RuntimeException recordFailure) {
                log.error("下载记录登记失败: fileMetaId={}", id, recordFailure);
            }
            log.info("GIS 文件下载已交付: id={}, originalName={}, sizeBytes={}, transferMode={}",
                    id, meta.getOriginalName(), result.contentLength(), result.transferMode());
        }
    }

    /** 下载及已管理文件处理统一验证工作区快照，发现变化后立即退出空间检索。 */
    public Path resolveSourcePath(GisFileMeta meta) {
        try {
            return workspaceResolver.resolveFile(meta);
        } catch (ControlledWorkspaceResolver.SourceException ex) {
            spatialMetadataService.markSourceUnavailable(meta.getId(), ex.code(), ex.getMessage());
            throw ex;
        }
    }

    /** 供文件处理编排读取完整的已入库元数据。 */
    public GisFileMeta getRequiredEntity(Long id) {
        return getRequired(id);
    }

    private GisFileMeta getRequired(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("文件元数据ID不能为空");
        }
        GisFileMeta meta = gisFileMetaMapper.selectById(id);
        if (meta == null) {
            throw new IllegalArgumentException("文件元数据不存在或已删除: " + id);
        }
        return meta;
    }

    private GisFileMeta getRequiredForUpdate(Long id) {
        if (id == null) throw new IllegalArgumentException("文件元数据ID不能为空");
        GisFileMeta meta = gisFileMetaMapper.selectActiveByIdForUpdate(id);
        if (meta == null) throw new IllegalArgumentException("文件元数据不存在或已删除: " + id);
        return meta;
    }

    private void ensureMutable(GisFileMeta meta) {
        if ("RUNNING".equals(meta.getMetadataStatus()) || gisFileTransferRecordItemMapper.selectCount(
                new LambdaQueryWrapper<GisFileTransferRecordItem>()
                        .eq(GisFileTransferRecordItem::getFileMetaId, meta.getId())
                        .eq(GisFileTransferRecordItem::getOperationStatus, "RUNNING")) > 0) {
            throw new IllegalStateException("文件导入或元数据提取进行中，暂不支持修改或删除");
        }
    }

    private void validatePermanentDeleteStorage(GisFileMeta meta) {
        if ("WORKSPACE".equals(meta.getSourceType())) return;
        if (trimToNull(meta.getStorageKey()) == null) {
            return;
        }
        String storageType = normalizeUpper(meta.getStorageType());
        if (!"LOCAL".equals(storageType)) {
            throw new IllegalStateException(
                    "暂不支持彻底删除 " + storageType + " 存储文件: " + meta.getId());
        }
    }

    /** 只有已入库且落在本地存储的文件才能直接下载。 */
    private void ensureDownloadable(GisFileMeta meta) {
        if (!"READY".equals(normalizeUpper(meta.getFileStatus()))) {
            throw new IllegalArgumentException("文件尚未入库，不能下载: " + meta.getId());
        }
        if (!"WORKSPACE".equals(meta.getSourceType()) && !"LOCAL".equals(normalizeUpper(meta.getStorageType()))) {
            throw new IllegalArgumentException(
                    "暂不支持下载 " + meta.getStorageType() + " 存储的文件: " + meta.getId());
        }
    }

    /** 认证入口写入的操作者用于下载记录留痕，未认证场景允许为空。 */
    private Long currentOperatorId() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        Object value = attributes.getRequest().getAttribute(CurrentOperator.REQUEST_ATTRIBUTE);
        return value instanceof CurrentOperator operator ? operator.userId() : null;
    }

    private void validateRequest(GisFileMetaVO reqVO, boolean requireId) {
        if (reqVO == null) {
            throw new IllegalArgumentException("文件元数据请求不能为空");
        }
        if (requireId && reqVO.getId() == null) {
            throw new IllegalArgumentException("文件元数据ID不能为空");
        }
        if (reqVO.getDataSetId() == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (reqVO.getVectorPlacement() != null && !Set.of("AUTO", "UNKNOWN").contains(reqVO.getVectorPlacement())) {
            throw new IllegalArgumentException("矢量归组方式须为AUTO或UNKNOWN");
        }
        requireText(reqVO.getOriginalName(), "原始文件名不能为空");
        requireText(reqVO.getStorageName(), "存储文件名不能为空");
        requireText(reqVO.getStorageKey(), "存储 Key 不能为空");
        if (reqVO.getSizeBytes() == null || reqVO.getSizeBytes() < 0) {
            throw new IllegalArgumentException("文件大小不能为空且不能小于0");
        }

        String storageType = normalizeUpper(reqVO.getStorageType());
        if (storageType != null && !STORAGE_TYPES.contains(storageType)) {
            throw new IllegalArgumentException("存储类型只能为 LOCAL、MINIO 或 S3");
        }
        String fileStatus = normalizeUpper(reqVO.getFileStatus());
        if (fileStatus != null && !FILE_STATUSES.contains(fileStatus)) {
            throw new IllegalArgumentException("文件状态只能为 PENDING、READY 或 FAILED");
        }
        String sha256 = trimToNull(reqVO.getSha256());
        if (sha256 != null && !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 必须为64位十六进制字符串");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void ensureDataSetExists(Long dataSetId) {
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + dataSetId);
        }
    }

    private void ensureStorageKeyAvailable(String storageType, String storageKey, Long excludedId) {
        LambdaQueryWrapper<GisFileMeta> query = new LambdaQueryWrapper<GisFileMeta>()
                .eq(GisFileMeta::getStorageType, storageType)
                .eq(GisFileMeta::getStorageKey, storageKey)
                .ne(excludedId != null, GisFileMeta::getId, excludedId);
        if (gisFileMetaMapper.exists(query)) {
            throw new IllegalArgumentException("存储位置已存在元数据: " + storageType + ":" + storageKey);
        }
    }

    private void applyEditableFields(
            GisFileMeta target, GisFileMetaVO source, String storageType, String storageKey) {
        target.setDataSetId(source.getDataSetId());
        target.setOriginalName(source.getOriginalName().trim());
        target.setStorageName(source.getStorageName().trim());
        target.setStorageKey(storageKey);
        target.setStorageType(storageType);
        target.setExtension(normalizeExtension(source.getExtension()));
        target.setSizeBytes(source.getSizeBytes());
        target.setSha256(normalizeLower(source.getSha256()));
        target.setCreatedBy(currentOperatorId());
        target.setErrorMessage(trimToNull(source.getErrorMessage()));
    }

    private GisFileMetaVO toVO(GisFileMeta meta) {
        GisFileMetaVO result = new GisFileMetaVO();
        result.setId(meta.getId());
        result.setDataSetId(meta.getDataSetId());
        result.setSourceType(meta.getSourceType());
        result.setWorkspaceCode(meta.getWorkspaceCode());
        result.setRelativePath(meta.getRelativePath());
        result.setSourceModifiedAt(meta.getSourceModifiedAt());
        result.setVectorDatasetStyleId(meta.getVectorDatasetStyleId());
        result.setVectorPlacement(meta.getVectorPlacement());
        result.setOriginalName(meta.getOriginalName());
        result.setStorageName(meta.getStorageName());
        result.setStorageKey(meta.getStorageKey());
        result.setStorageType(meta.getStorageType());
        result.setExtension(meta.getExtension());
        result.setSizeBytes(meta.getSizeBytes());
        result.setSha256(meta.getSha256());
        result.setCreatedBy(meta.getCreatedBy());
        result.setFileStatus(meta.getFileStatus());
        result.setCleanupStatus(meta.getCleanupStatus());
        result.setErrorMessage(meta.getErrorMessage());
        result.setMetadataStatus(meta.getMetadataStatus());
        result.setMetadataVersion(meta.getMetadataVersion());
        result.setMetadataErrorCode(meta.getMetadataErrorCode());
        result.setMetadataErrorMessage(meta.getMetadataErrorMessage());
        result.setMetadataExtractedAt(meta.getMetadataExtractedAt());
        result.setCreateTime(meta.getCreateTime());
        result.setUpdateTime(meta.getUpdateTime());
        return result;
    }

    private GisFileMetaVO toVOWithCategory(GisFileMeta meta) {
        GisFileMetaVO result = toVO(meta);
        populateCategoryIds(List.of(result));
        spatialMetadataService.populate(List.of(result), true);
        return result;
    }

    private void populateCategoryIds(List<GisFileMetaVO> records) {
        Set<Long> dataSetIds = records.stream()
                .map(GisFileMetaVO::getDataSetId)
                .collect(Collectors.toSet());
        if (dataSetIds.isEmpty()) {
            return;
        }
        Map<Long, GisDataSet> dataSetsById = gisDataSetMapper.selectList(
                        new LambdaQueryWrapper<GisDataSet>().in(GisDataSet::getId, dataSetIds))
                .stream()
                .collect(Collectors.toMap(GisDataSet::getId, Function.identity()));
        records.forEach(record -> {
            GisDataSet dataSet = dataSetsById.get(record.getDataSetId());
            if (dataSet != null) {
                record.setCategoryId(dataSet.getCategoryId());
            }
        });
    }

    private String defaultStatus(String value) {
        String normalized = normalizeUpper(value);
        return normalized == null ? "READY" : normalized;
    }

    private String normalizeExtension(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return trimToNull(normalized) == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeUpper(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeLower(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
