package org.ocean.admin.gis.progress;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisFileTransferRecord;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSetArchive;
import org.ocean.admin.gis.mapper.GisFileTransferRecordMapper;
import org.ocean.admin.gis.mapper.GisProcessingTaskMapper;
import org.ocean.admin.gis.mapper.GisTileSetArchiveMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.ocean.admin.gis.progress.ProgressSnapshot.*;

/** 从权威业务记录重建快照；仅恢复可确认指标，不恢复后台执行。 */
@Component
@RequiredArgsConstructor
public class ProgressSnapshotProvider {
    private final GisFileTransferRecordMapper uploads;
    private final GisProcessingTaskMapper processing;
    private final GisTileSetArchiveMapper archives;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProgressSnapshot rebuild(String id) {
        Long epoch;
        if (id.startsWith("upload_")) epoch = uploads.nextProgressEpoch(id);
        else if (id.startsWith("archive_")) epoch = archives.nextProgressEpoch(id);
        else if (id.startsWith("terrain_") || id.startsWith("imagery_") || id.startsWith("vector_"))
            epoch = processing.nextProgressEpoch(id);
        else return null;
        return epoch == null ? null : load(id);
    }

    /** 无副作用地读取业务状态，供补查修复丢失的终态通知。 */
    public ProgressSnapshot load(String id) {
        if (id.startsWith("upload_")) {
            GisFileTransferRecord r = uploads.selectOne(new LambdaQueryWrapper<GisFileTransferRecord>()
                    .eq(GisFileTransferRecord::getRecordNo, id).eq(GisFileTransferRecord::getOperationType, "IMPORT"));
            if (r == null) return null;
            long epoch = Math.max(1, r.getProgressEpoch());
            return snapshot(id, r.getRequestId(), "GIS_UPLOAD", null, "GIS文件接入", r.getRecordStatus(),
                    r.getCurrentStage(), new ProgressSummary(r.getTotalCount(), r.getCompletedCount(),
                            r.getFailedCount(), ProgressUnit.FILE),
                    List.of(new Metric("CONFIRMED_BYTES", "确认落盘字节", r.getUploadedBytes(),
                            r.getTotalBytes(), ProgressUnit.BYTE)),
                    new Resource("FILE_TRANSFER_RECORD", id, "/api/gis/file-transfer-records/" + id),
                    r.getErrorMessage(), r.getStartTime(), r.getFinishTime(), epoch);
        }
        if (id.startsWith("archive_")) {
            GisTileSetArchive r = archives.selectOne(new LambdaQueryWrapper<GisTileSetArchive>()
                    .eq(GisTileSetArchive::getArchiveNo, id));
            if (r == null) return null;
            long epoch = Math.max(1, r.getProgressEpoch());
            boolean known = r.getTotalFileCount() > 0;
            return snapshot(id, null, "GIS_TILE_SET_ARCHIVE", null, r.getArchiveName(), r.getArchiveStatus(),
                    r.getCurrentStage(), new ProgressSummary(1, r.getArchiveStatus() == TaskStatus.COMPLETED ? 1 : 0,
                            r.getArchiveStatus() == TaskStatus.FAILED ? 1 : 0, ProgressUnit.FILE),
                    List.of(new Metric("PACKED_FILES", "已打包文件", r.getProcessedFileCount(),
                                    known ? r.getTotalFileCount() : null, ProgressUnit.FILE),
                            new Metric("PACKED_BYTES", "已打包源字节", r.getProcessedBytes(),
                                    known ? r.getTotalBytes() : null, ProgressUnit.BYTE)),
                    new Resource("TILE_SET_ARCHIVE", id, "/api/gis/tile-set-archives/" + id),
                    r.getErrorMessage(), r.getStartTime(), r.getFinishTime(), epoch);
        }
        if (id.startsWith("terrain_") || id.startsWith("imagery_") || id.startsWith("vector_")) {
            GisProcessingTask r = processing.selectOne(new LambdaQueryWrapper<GisProcessingTask>()
                    .eq(GisProcessingTask::getTaskNo, id));
            if (r == null) return null;
            long epoch = Math.max(1, r.getProgressEpoch());
            return snapshot(id, null, "GIS_PROCESSING", r.getProcessingType().name(), r.getTaskName(),
                    r.getTaskStatus(), r.getCurrentStage(), new ProgressSummary(r.getTotalCount(),
                            r.getCompletedCount(), r.getFailedCount(), ProgressUnit.INPUT),
                    List.of(), new Resource("PROCESSING_TASK", r.getId().toString(), "/api/gis/task/" + r.getId()),
                    r.getErrorMessage(), r.getStartTime(), r.getFinishTime(), epoch);
        }
        return null;
    }

    private ProgressSnapshot snapshot(String id, String correlation, String type, String category,
            String title, TaskStatus status, String stage, ProgressSummary summary, List<Metric> metrics,
            Resource resource, String error, LocalDateTime start, LocalDateTime finish, long epoch) {
        return new ProgressSnapshot(id, correlation, type, category, title, status,
                Overall.of(status == TaskStatus.COMPLETED ? 100 : null),
                ProgressStage.indeterminate(stage == null ? status.name() : stage, "根据业务记录恢复"),
                metrics, summary, resource, error == null ? null : new ErrorInfo("BUSINESS_FAILURE", error),
                start, status.isTerminal() ? (finish == null ? LocalDateTime.now() : finish) : null,
                new Revision(epoch, 0));
    }
}
