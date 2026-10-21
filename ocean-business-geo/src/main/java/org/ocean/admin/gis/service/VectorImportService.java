package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.dto.TaskProgressUnit;
import org.ocean.admin.gis.dto.TaskProgressUpdate;
import org.ocean.admin.gis.dto.TaskStage;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileTransferRecord;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorImportTask;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.util.GeoJsonFeatureReader;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** SINGLE 矢量导入；事务只包围领取、批次写入和最终可见性切换。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VectorImportService {
    private final GisVectorMapper mapper;
    private final GisDataSetMapper dataSets;
    private final VectorLayerService layers;
    private final GeoJsonFeatureReader reader;
    private final FileUploadUtil files;
    private final AsyncTaskService progress;
    private final TransactionTemplate transactions;
    private final FileTransferRecordService transfers;
    private final VectorStyleService styles;
    private final VectorGeneralizationService generalization;

    public GisVectorVO.ImportTask submit(GisVectorRequests.Import request) {
        if (request == null || request.fileMetaId() == null || request.fileMetaId() < 1
                || request.layerName() == null || request.layerName().isBlank() || request.layerName().trim().length() > 100
                || request.requestId() == null || request.requestId().isBlank() || request.requestId().trim().length() > 128) {
            throw badRequest("文件ID、图层名称及requestId不能为空且须符合长度限制");
        }
        String mode = request.splitMode() == null ? "SINGLE" : request.splitMode();
        if (!"SINGLE".equals(mode)) throw badRequest("首期仅支持 SINGLE 分层方式");
        Long operatorId = currentOperatorId();
        GisVectorImportTask task;
        try {
            task = transactions.execute(status -> {
                GisVectorImportTask existing = mapper.findTaskByRequest(request.requestId().trim());
                if (existing != null) return verifyReplay(existing, request, operatorId);
                GisFileMeta source = mapper.lockSource(request.fileMetaId());
                validateSource(source);
                var dataSet = dataSets.selectById(source.getDataSetId());
                if (dataSet == null || !Long.valueOf(2).equals(dataSet.getCategoryId())) throw badRequest("来源文件必须属于有效的矢量数据集");
                return createTask(source, request.layerName().trim(), request.requestId().trim(), operatorId);
            });
        } catch (DuplicateKeyException ex) {
            GisVectorImportTask existing = mapper.findTaskByRequest(request.requestId().trim());
            if (existing == null) throw ex;
            task = verifyReplay(existing, request, operatorId);
        }
        return detail(Objects.requireNonNull(task).getTaskNo());
    }

    /** 元数据事务内登记后续待办，避免提交后进程退出导致漏建图层。 */
    @Transactional(rollbackFor = Exception.class)
    public void enqueueUploadedSource(GisFileMeta source) {
        var dataSet = dataSets.selectById(source.getDataSetId());
        if (dataSet == null || !Long.valueOf(2).equals(dataSet.getCategoryId())
                || !("geojson".equals(source.getExtension()) || "json".equals(source.getExtension()))) return;
        var item = transfers.runningImportItem(source.getId());
        if (item == null || item.getVectorImportTaskId() != null) return;
        validateSource(source);
        String name = source.getOriginalName();
        int suffix = name.lastIndexOf('.');
        if (suffix > 0) name = name.substring(0, suffix);
        name = name.trim();
        if (name.isBlank()) name = "矢量图层";
        name = name.substring(0, name.offsetByCodePoints(0, Math.min(100, name.codePointCount(0, name.length()))));
        var task = createTask(source, name, "upload:" + UUID.randomUUID(), source.getUploadedBy());
        transfers.attachVectorImport(item.getId(), task.getId());
    }

    private GisVectorImportTask createTask(GisFileMeta source, String layerName, String requestId, Long operatorId) {
        GisVectorImportTask task = new GisVectorImportTask();
        task.setId(IdWorker.getId());
        task.setTaskNo(TaskNoGenerator.generateVectorImportTaskNo());
        task.setRequestId(requestId);
        task.setSourceFileId(source.getId());
        task.setDataSetId(source.getDataSetId());
        task.setSourceStorageKey(source.getStorageKey());
        task.setSourceSha256(source.getSha256());
        task.setSourceSizeBytes(source.getSizeBytes());
        task.setSourceMetadataVersion(source.getMetadataVersion());
        task.setLayerName(layerName);
        task.setSplitMode("SINGLE");
        task.setOperatorId(operatorId);
        mapper.insertTask(task);
        GisVectorLayer layer = new GisVectorLayer();
        layer.setId(IdWorker.getId());
        layer.setImportTaskId(task.getId());
        layer.setDataSetId(source.getDataSetId());
        layer.setSourceFileId(source.getId());
        layer.setLayerKey("default");
        layer.setLayerName(layerName);
        mapper.insertLayer(layer);
        styles.createDefault(layer.getId());
        return task;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisVectorVO.ImportTask detail(String taskNo) {
        GisVectorImportTask task = requireTask(taskNo);
        return new GisVectorVO.ImportTask(task.getTaskNo(), task.getRequestId(), task.getTaskStatus(),
                task.getProcessedFeatureCount(), task.getErrorFeatureIndex(), task.getErrorMessage(),
                mapper.taskLayers(task.getId()).stream().map(layers::toVO).toList(), task.getCreateTime(), task.getFinishTime());
    }

    @Transactional(rollbackFor = Exception.class)
    public GisVectorVO.ImportTask retry(String taskNo) {
        GisVectorImportTask task = requireTask(taskNo);
        if (!"FAILED".equals(task.getTaskStatus())) throw conflict("仅失败的矢量导入任务可重试");
        validateSnapshot(task);
        if (mapper.taskLayers(task.getId()).isEmpty()) throw conflict("导入图层已删除，不能重试");
        if (mapper.retry(task.getId()) != 1) throw conflict("导入任务状态已变化");
        mapper.resetLayers(task.getId());
        transfers.reopenVectorImport(task.getId());
        return detail(taskNo);
    }

    public GisVectorVO.ImportTask retryUploadTask(Long taskId) {
        var task = mapper.findTaskById(taskId);
        if (task == null) throw conflict("矢量任务不存在");
        return transactions.execute(status -> retry(task.getTaskNo()));
    }

    public void process(Long taskId) {
        GisVectorImportTask claim = transactions.execute(status -> {
            GisVectorImportTask task = mapper.claim(taskId);
            if (task != null) {
                mapper.deleteImportedFeatures(taskId);
                mapper.resetLayers(taskId);
            }
            return task;
        });
        if (claim == null) return;
        GisFileTransferRecord upload = transfers.vectorImportRecord(claim.getId());
        long[] written = {0};
        try {
            validateSnapshot(claim);
            var path = files.resolveStoredPath(claim.getSourceStorageKey());
            if (!Files.isRegularFile(path) || Files.size(path) != claim.getSourceSizeBytes()) throw badRequest("来源文件不存在或大小已变化");
            List<GisVectorLayer> taskLayers = mapper.taskLayers(taskId);
            if (taskLayers.size() != 1 || !"SINGLE".equals(claim.getSplitMode())) throw badRequest("当前任务不是有效的 SINGLE 导入");
            Long layerId = taskLayers.getFirst().getId();
            if (upload == null) notifyProgress(() -> progress.registerTask(claim.getTaskNo(), claim.getRequestId(),
                    "矢量要素导入", 1, "GIS_VECTOR_IMPORT", TaskProgressUnit.FILE));
            else transfers.publishImportProgress(upload);
            List<GisVectorFeature> batch = new ArrayList<>();
            long[] characters = {0};
            Runnable heartbeat = heartbeat(claim);
            long count = reader.read(path, parsed -> {
                GisVectorFeature feature = new GisVectorFeature();
                feature.setId(IdWorker.getId());
                feature.setLayerId(layerId);
                feature.setGeometryJson(parsed.geometry());
                feature.setPropertiesJson(parsed.properties());
                feature.setSourceFeatureId(parsed.sourceId());
                feature.setSourceFeatureIndex(parsed.index());
                batch.add(feature);
                characters[0] += parsed.geometry().length() + parsed.properties().length();
                if (batch.size() >= 100 || characters[0] >= 2 * 1024 * 1024) {
                    flush(claim, batch, written, upload);
                    characters[0] = 0;
                }
            }, heartbeat);
            flush(claim, batch, written, upload);
            if (count == 0) throw badRequest("文件不包含可制图要素");
            transactions.executeWithoutResult(status -> {
                requireClaim(claim);
                validateSnapshot(claim);
                mapper.refreshLayer(layerId);
                mapper.setLayersStatus(taskId, "READY");
                generalization.markDirty(layerId);
                mapper.finishTask(taskId, claim.getVersion(), "COMPLETED", count, null, null);
                transfers.completeVectorImport(taskId, true, null);
            });
            if (upload == null) notifyProgress(() -> {
                progress.updateProgress(claim.getTaskNo(), TaskProgressUpdate.itemFinished(true));
                progress.finalizeTaskResult(claim.getTaskNo(), "矢量导入完成，共" + count + "个要素");
            });
        } catch (LeaseLostException ex) {
            log.info("矢量导入执行权已变更，停止旧任务: {}", claim.getTaskNo());
        } catch (Exception ex) {
            Long index = ex instanceof GeoJsonFeatureReader.FeatureReadException error ? error.featureIndex() : null;
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (message.length() > 1000) message = message.substring(0, 1000);
            String errorMessage = message;
            Boolean failed = transactions.execute(status -> {
                if (mapper.lockClaim(taskId, claim.getVersion()) == null) return false;
                mapper.deleteImportedFeatures(taskId);
                mapper.resetLayers(taskId);
                mapper.setLayersStatus(taskId, "FAILED");
                mapper.finishTask(taskId, claim.getVersion(), "FAILED", written[0], index, errorMessage);
                transfers.completeVectorImport(taskId, false, errorMessage);
                return true;
            });
            if (Boolean.TRUE.equals(failed) && upload == null) notifyProgress(() -> progress.finalizeTaskFailure(claim.getTaskNo(), errorMessage));
            log.warn("矢量导入失败: taskNo={}, featureIndex={}, reason={}", claim.getTaskNo(), index, errorMessage);
        }
    }

    private void flush(GisVectorImportTask claim, List<GisVectorFeature> batch, long[] written, GisFileTransferRecord upload) {
        if (batch.isEmpty()) return;
        long count = written[0] + batch.size();
        transactions.executeWithoutResult(status -> {
            requireClaim(claim);
            GisVectorVO.GeometryError error = mapper.firstGeometryError(batch);
            if (error != null) throw new GeoJsonFeatureReader.FeatureReadException(error.featureIndex(), error.reason());
            if (mapper.insertFeatures(batch) != batch.size()) throw new IllegalStateException("矢量批次写入数量不一致");
            mapper.updateProgress(claim.getId(), claim.getVersion(), count);
        });
        written[0] = count;
        batch.clear();
        if (upload == null) notifyProgress(() -> progress.updateProgress(claim.getTaskNo(),
                TaskProgressUpdate.stageChanged(TaskStage.indeterminate("IMPORTING", "已写入" + count + "个要素"))));
        else transfers.publishImportProgress(upload);
    }

    private Runnable heartbeat(GisVectorImportTask claim) {
        long[] renewed = {System.nanoTime()};
        return () -> {
            long now = System.nanoTime();
            if (now - renewed[0] < TimeUnit.SECONDS.toNanos(30)) return;
            if (mapper.renewLease(claim.getId(), claim.getVersion()) != 1) throw new LeaseLostException();
            renewed[0] = now;
        };
    }

    private void requireClaim(GisVectorImportTask task) {
        if (mapper.lockClaim(task.getId(), task.getVersion()) == null) throw new LeaseLostException();
    }

    private void validateSnapshot(GisVectorImportTask task) {
        GisFileMeta source = task.getSourceFileId() == null ? null : mapper.lockSource(task.getSourceFileId());
        validateSource(source);
        if (!Objects.equals(source.getStorageKey(), task.getSourceStorageKey())
                || !Objects.equals(source.getSha256(), task.getSourceSha256())
                || !Objects.equals(source.getSizeBytes(), task.getSourceSizeBytes())
                || !Objects.equals(source.getMetadataVersion(), task.getSourceMetadataVersion())
                || !Objects.equals(source.getDataSetId(), task.getDataSetId())) throw conflict("来源文件信息已变化，请重新提交导入");
        if (dataSets.selectById(task.getDataSetId()) == null) throw conflict("所属数据集已删除");
    }

    private void validateSource(GisFileMeta source) {
        if (source == null) throw badRequest("来源文件不存在或已删除");
        if (!"LOCAL".equals(source.getStorageType()) || !"READY".equals(source.getUploadStatus())
                || !"COMPLETED".equals(source.getMetadataStatus())
                || !("geojson".equals(source.getExtension()) || "json".equals(source.getExtension()))) {
            throw badRequest("仅支持上传完整且空间元数据已完成的 LOCAL GeoJSON 文件");
        }
    }

    private GisVectorImportTask verifyReplay(GisVectorImportTask task, GisVectorRequests.Import request, Long operatorId) {
        if (!Objects.equals(task.getSourceFileId(), request.fileMetaId())
                || !Objects.equals(task.getLayerName(), request.layerName().trim())
                || !Objects.equals(task.getOperatorId(), operatorId)) throw conflict("requestId 已被不同的导入参数或操作人使用");
        return task;
    }

    private GisVectorImportTask requireTask(String taskNo) {
        GisVectorImportTask task = mapper.findTask(taskNo);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "矢量导入任务不存在");
        return task;
    }

    private Long currentOperatorId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(CurrentOperator.REQUEST_ATTRIBUTE) instanceof CurrentOperator operator) {
            return operator.userId();
        }
        return null;
    }

    private void notifyProgress(Runnable notification) {
        try { notification.run(); }
        catch (RuntimeException ex) { log.warn("矢量进度通知失败，持久化状态已保留: {}", ex.getMessage()); }
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static final class LeaseLostException extends RuntimeException { }
}
