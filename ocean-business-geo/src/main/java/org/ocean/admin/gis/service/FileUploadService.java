package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.config.FileUploadConfig;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.GisUploadChunkCommand;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileSpatialMetadataMapper;
import org.ocean.admin.gis.progress.*;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/** 接收分块并登记真实文件；整批传输结束后统一放行空间解析。 */
@Service
@RequiredArgsConstructor
public class FileUploadService {
    private final GisDataSetMapper dataSets;
    private final GisFileMetaMapper files;
    private final GisFileSpatialMetadataMapper spatial;
    private final FileUploadUtil storage;
    private final FileUploadConfig config;
    private final RealtimeProgressService progress;
    private final FileSpatialMetadataService metadata;
    private final VectorDataSetStyleService styles;
    private final DataSetService dataSetService;
    private final TransactionTemplate transactions;

    public Map<String, Object> uploadChunk(GisUploadChunkCommand command, InputStream input) {
        validate(command);
        Long operator = operatorId();
        String id = "upload_" + (operator == null ? "0" : operator) + "_" + command.requestId();
        return progress.withExecutionLock(id, () -> {
            try { return receive(id, operator, command, input); }
            catch (IllegalArgumentException invalid) { throw invalid; }
            catch (RuntimeException failure) {
                try { progress.interruptIngestion(id); }
                catch (RuntimeException notification) { failure.addSuppressed(notification); }
                throw failure;
            }
        });
    }

    private Map<String, Object> receive(String id, Long operator, GisUploadChunkCommand command, InputStream input) {
        GisDataSet dataSet = dataSets.selectById(command.dataSetId());
        if (dataSet == null) throw new IllegalArgumentException("数据集不存在或已删除");
        validateType(dataSet, command.fileName());
        ProgressSnapshot snapshot = progress.get(id);
        if (snapshot == null) {
            if (command.fileIndex() != 0 || command.offset() != 0)
                throw new IllegalStateException("上传进度不存在，请从第一个文件重新录入");
            progress.create(id, command.requestId(), "客户端接入：" + dataSet.getDataSetName(), command.fileCount(),
                    "GIS_UPLOAD", ProgressUnit.FILE, new ProgressSnapshot.Resource("DATA_SET", command.dataSetId().toString(),
                            "/api/gis/dataSet/" + command.dataSetId()));
            progress.update(progress.handle(id), ProgressUpdate.metric("TOTAL_BYTES", "声明总字节", command.totalSize(),
                    command.totalSize(), ProgressUnit.BYTE), ProgressUpdate.stageChanged(
                    ProgressStage.determinate("UPLOADING", "接收并登记文件", 0, command.totalSize(), ProgressUnit.BYTE)));
            snapshot = progress.query(id);
        }
        if (!snapshot.resource().id().equals(command.dataSetId().toString()) || snapshot.summary().total() != command.fileCount()
                || metric(snapshot, "TOTAL_BYTES") != command.totalSize())
            throw new IllegalArgumentException("同一上传标识的目标或声明总量不能变更");
        String storageKey = storage.uploadStorageKey(command.dataSetId(), id, command.fileIndex(), command.fileName());
        GisFileMeta existing = files.selectOne(new LambdaQueryWrapper<GisFileMeta>().eq(GisFileMeta::getStorageType, "LOCAL")
                .eq(GisFileMeta::getStorageKey, storageKey));
        if (existing != null) {
            if (!existing.getOriginalName().equals(command.fileName()) || existing.getSizeBytes() != command.fileSize())
                throw new IllegalArgumentException("同一文件的名称或声明大小不能变更");
            consume(input, command.chunkSize());
            if (command.lastFile() && !snapshot.status().isTerminal()) beginExtraction(id, command);
            return response(id, command, command.fileSize(), true, true);
        }
        if (snapshot.status().isTerminal() || !"UPLOADING".equals(snapshot.stage().code()))
            throw new IllegalStateException("该次接入已结束传输，请使用新的上传标识重新录入");
        List<GisFileMeta> registered = files.selectList(new LambdaQueryWrapper<GisFileMeta>()
                .eq(GisFileMeta::getIngestProgressId, id).orderByAsc(GisFileMeta::getIngestFileIndex));
        if (registered.size() != command.fileIndex()) throw new IllegalArgumentException("必须按文件顺序上传");
        if (!snapshot.activeFiles().isEmpty()) {
            var current = snapshot.activeFiles().getFirst();
            if (current.fileId().equals(Integer.toString(command.fileIndex()))
                    && (!current.fileName().equals(command.fileName())
                        || (current.stage().unit() == ProgressUnit.BYTE && current.stage().total() != null
                            && current.stage().total() != command.fileSize())))
                throw new IllegalArgumentException("同一文件的名称或声明大小不能变更");
        }
        if (!registered.isEmpty() && registered.getFirst().getVectorPlacement() != null
                && !registered.getFirst().getVectorPlacement().equals(command.vectorPlacement()))
            throw new IllegalArgumentException("同一次接入的矢量归组方式不能变更");
        long previousBytes = registered.stream().mapToLong(GisFileMeta::getSizeBytes).sum();
        if (previousBytes + command.fileSize() > command.totalSize()
                || (command.lastFile() && previousBytes + command.fileSize() != command.totalSize()))
            throw new IllegalArgumentException("文件大小合计与声明总量不一致");
        storage.ensureFreeSpace(Math.addExact(command.chunkSize(), config.getMinFreeSpace().toBytes()));
        Consumer<ProgressStage> report = ProgressStage.throttled(work -> {
            spatial.renewIngestionWaiting(id, LocalDateTime.now().plus(config.getIdleTimeout()));
            progress.update(progress.handle(id), ProgressUpdate.stageChanged(ProgressStage.determinate("UPLOADING",
                    "接收文件：" + command.fileName(), Math.min(command.totalSize(), previousBytes +
                    (work.unit() == ProgressUnit.BYTE && work.completed() != null ? work.completed() : command.offset())),
                    command.totalSize(), ProgressUnit.BYTE)),
                    ProgressUpdate.activeFilesChanged(List.of(new ProgressSnapshot.FileWork(Integer.toString(command.fileIndex()),
                            command.fileName(), work, TaskStatus.RUNNING, 0))));
        });
        GisStoredFile stored = storage.findCompletedFile(command.dataSetId(), id, command.fileIndex(), command.fileName(),
                command.fileSize(), report);
        long nextOffset;
        boolean duplicate;
        if (stored != null) {
            consume(input, command.chunkSize());
            nextOffset = command.fileSize(); duplicate = true;
        } else {
            var write = storage.appendChunk(id, command.fileIndex(), command.offset(), command.fileSize(), command.chunkSize(), input, report, () -> progress.checkExecutionLease(id));
            nextOffset = write.nextOffset(); duplicate = write.duplicate();
            progress.assertExecutionLock(id);
            if (write.fileCompleted()) stored = storage.completeStagedFile(command.dataSetId(), id, command.fileIndex(),
                    command.fileName(), command.fileSize(), write.sha256(), report);
        }
        if (stored != null) {
            GisStoredFile complete = stored;
            try { transactions.executeWithoutResult(status -> register(id, operator, command, complete)); }
            catch (RuntimeException failure) {
                try { storage.restoreCompletedFile(command.dataSetId(), id, command.fileIndex(), command.fileName()); }
                catch (RuntimeException restore) { failure.addSuppressed(restore); }
                throw failure;
            }
        }
        progress.update(progress.handle(id), ProgressUpdate.metric("CONFIRMED_BYTES", "确认落盘字节", previousBytes + nextOffset,
                command.totalSize(), ProgressUnit.BYTE), ProgressUpdate.stageChanged(ProgressStage.determinate("UPLOADING",
                stored == null ? "正在上传：" + command.fileName() : "文件登记完成", previousBytes + nextOffset,
                command.totalSize(), ProgressUnit.BYTE)), ProgressUpdate.activeFilesChanged(stored == null
                        ? List.of(new ProgressSnapshot.FileWork(Integer.toString(command.fileIndex()), command.fileName(),
                            ProgressStage.determinate("UPLOADING", "当前文件已落盘", nextOffset, command.fileSize(), ProgressUnit.BYTE),
                            TaskStatus.RUNNING, 0)) : List.of()));
        if (stored != null && command.lastFile()) beginExtraction(id, command);
        return response(id, command, nextOffset, duplicate, stored != null);
    }

    private void register(String id, Long operator, GisUploadChunkCommand command, GisStoredFile stored) {
        progress.assertExecutionLock(id);
        GisDataSet target = dataSets.selectActiveByIdForUpdate(command.dataSetId());
        if (target == null) throw new IllegalArgumentException("数据集已删除");
        GisFileMeta meta = new GisFileMeta();
        meta.setId(IdWorker.getId()); meta.setDataSetId(target.getId());
        meta.setIngestProgressId(id); meta.setIngestFileIndex(command.fileIndex());
        meta.setOriginalName(command.fileName()); meta.setStorageName(stored.getStorageName());
        meta.setStorageKey(stored.getStorageKey()); meta.setStorageType(stored.getStorageType());
        meta.setSizeBytes(stored.getSizeBytes()); meta.setSha256(stored.getSha256());
        meta.setExtension(FileUploadUtil.extensionOf(command.fileName())); meta.setCreatedBy(operator);
        meta.setFileStatus("READY"); meta.setCleanupStatus("NOT_REQUIRED"); meta.setMetadataStatus("WAITING");
        meta.setMetadataVersion(0L); meta.setMetadataLeaseExpiresAt(LocalDateTime.now().plus(config.getIdleTimeout()));
        meta.setDeleted(0); meta.setCreateTime(LocalDateTime.now()); meta.setUpdateTime(meta.getCreateTime());
        if (Long.valueOf(2).equals(target.getCategoryId())) {
            meta.setVectorDatasetStyleId(styles.require(target.getId(), false).getId());
            meta.setVectorPlacement(command.vectorPlacement());
        }
        if (files.insert(meta) != 1) throw new IllegalStateException("文件登记失败");
        dataSetService.updateFileStatistics(null, meta);
    }

    private void beginExtraction(String id, GisUploadChunkCommand command) {
        ProgressSnapshot current = progress.query(id);
        if (current.status().isTerminal()) return;
        long count = files.selectCount(new LambdaQueryWrapper<GisFileMeta>().eq(GisFileMeta::getIngestProgressId, id));
        if (count != command.fileCount()) throw new IllegalStateException("整批文件尚未登记完成");
        progress.update(current.handle(), ProgressUpdate.stageChanged(ProgressStage.determinate("EXTRACTING_METADATA",
                "整批传输结束，提取空间元数据", 0, command.fileCount(), ProgressUnit.FILE)),
                ProgressUpdate.overallProgressChanged(0), ProgressUpdate.activeFilesChanged(List.of()));
        metadata.releaseIngestion(id);
        storage.deleteEmptyBatchDirectory(id);
        progress.refreshIngestion(id);
    }

    private Map<String, Object> response(String id, GisUploadChunkCommand command, long offset, boolean duplicate, boolean complete) {
        ProgressSnapshot snapshot = progress.query(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", command.requestId()); result.put("progress", ProgressSnapshot.reference(id, "GIS_UPLOAD", snapshot.status()));
        result.put("dataSetId", command.dataSetId()); result.put("fileIndex", command.fileIndex());
        result.put("nextOffset", offset); result.put("duplicate", duplicate); result.put("fileCompleted", complete);
        result.put("importCompleted", snapshot.status().isTerminal()); result.put("currentStage", snapshot.stage().code());
        result.put("fileFailed", false); result.put("taskCompleted", snapshot.status().isTerminal());
        result.put("completedCount", snapshot.summary().succeeded()); result.put("failedCount", snapshot.summary().failed());
        result.put("status", snapshot.status());
        return result;
    }

    public int cleanupExpiredBatches() { return storage.cleanupExpiredUploads(config.getIdleTimeout(), progress); }

    private void validate(GisUploadChunkCommand command) {
        if (command == null || !command.requestId().matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("上传标识无效");
        if (command.fileCount() > 100 || command.fileSize() > config.getMaxFileSize().toBytes()
                || command.totalSize() > config.getMaxBatchSize().toBytes() || command.chunkSize() > config.getMaxChunkSize().toBytes())
            throw new IllegalArgumentException("上传文件数量或大小超过配置上限");
        if (command.fileName().length() > 255 || command.fileName().contains("/") || command.fileName().contains("\\")
                || command.fileName().contains("\0") || command.fileName().isBlank()) throw new IllegalArgumentException("文件名无效");
    }

    private void validateType(GisDataSet target, String name) {
        Set<String> allowed = switch (target.getCategoryId().intValue()) {
            case 0 -> Set.of("tif", "tiff", "img", "jp2", "png", "jpg", "jpeg", "tfw", "prj", "aux", "xml");
            case 1 -> Set.of("tif", "tiff", "dem", "hgt", "terrain", "asc", "prj", "xml");
            case 2 -> Set.of("shp", "shx", "dbf", "prj", "cpg", "sbn", "sbx", "geojson", "json", "kml", "kmz", "gpkg");
            default -> throw new IllegalArgumentException("数据集分类无效");
        };
        if (!allowed.contains(FileUploadUtil.extensionOf(name))) throw new IllegalArgumentException("文件格式与数据集分类不匹配");
    }

    private long metric(ProgressSnapshot snapshot, String code) {
        return snapshot.metrics().stream().filter(value -> code.equals(value.code())).mapToLong(ProgressSnapshot.Metric::value).findFirst().orElse(-1);
    }

    private Long operatorId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        HttpServletRequest request = attributes.getRequest();
        return request.getAttribute(CurrentOperator.REQUEST_ATTRIBUTE) instanceof CurrentOperator operator ? operator.userId() : null;
    }

    private void consume(InputStream input, long size) {
        try { if (input.transferTo(java.io.OutputStream.nullOutputStream()) != size) throw new IllegalArgumentException("分块内容长度不符"); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
}
