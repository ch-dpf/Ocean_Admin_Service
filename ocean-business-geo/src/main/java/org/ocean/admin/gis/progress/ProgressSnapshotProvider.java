package org.ocean.admin.gis.progress;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSetArchive;
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
    private final GisFileMetaMapper files;
    private final GisProcessingTaskMapper processing;
    private final GisTileSetArchiveMapper archives;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProgressSnapshot rebuild(String id) {
        Long epoch;
        if (id.startsWith("archive_")) epoch = archives.nextProgressEpoch(id);
        else if (id.startsWith("terrain_") || id.startsWith("imagery_") || id.startsWith("vector_"))
            epoch = processing.nextProgressEpoch(id);
        else return null;
        return epoch == null ? null : load(id);
    }

    /** 无副作用地读取业务状态，供补查修复丢失的终态通知。 */
    public ProgressSnapshot load(String id) {
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

    public GisFileMeta ingestionFile(Long id) { return files.selectById(id); }

    public boolean isExtracting(String id) {
        GisFileMeta file = files.selectById(Long.valueOf(id));
        return file != null && "RUNNING".equals(file.getMetadataStatus());
    }

    public List<String> ingestionIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return files.selectList(new LambdaQueryWrapper<GisFileMeta>().in(GisFileMeta::getId, ids))
                .stream().map(GisFileMeta::getIngestProgressId).filter(java.util.Objects::nonNull).distinct().toList();
    }

    public ProgressSummary ingestionSummary(String id, ProgressSnapshot snapshot) {
        var counts = files.ingestionCounts(id);
        long registrationFailed = snapshot.metrics().stream().filter(metric -> "REGISTRATION_FAILED".equals(metric.code()))
                .mapToLong(Metric::value).findFirst().orElse(0);
        return new ProgressSummary(snapshot.summary().total(), counts.succeeded(), counts.failed() + registrationFailed,
                snapshot.summary().skipped(), ProgressUnit.FILE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failWaitingIngestion(String id) {
        files.update(null, new LambdaUpdateWrapper<GisFileMeta>().eq(GisFileMeta::getIngestProgressId, id)
                .eq(GisFileMeta::getMetadataStatus, "WAITING").set(GisFileMeta::getMetadataStatus, "FAILED")
                .set(GisFileMeta::getMetadataLeaseExpiresAt, null).setSql("metadata_version = metadata_version + 1")
                .set(GisFileMeta::getMetadataErrorCode, "INGESTION_INTERRUPTED")
                .set(GisFileMeta::getMetadataErrorMessage, "接入中断，请删除失败记录后重新录入")
                .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void detachIngestion(String id) {
        files.update(null, new LambdaUpdateWrapper<GisFileMeta>().eq(GisFileMeta::getIngestProgressId, id)
                .set(GisFileMeta::getIngestProgressId, null).set(GisFileMeta::getIngestFileIndex, null));
    }

    private ProgressSnapshot snapshot(String id, String correlation, String type, String category,
            String title, TaskStatus status, String stage, ProgressSummary summary, List<Metric> metrics,
            Resource resource, String error, LocalDateTime start, LocalDateTime finish, long epoch) {
        return new ProgressSnapshot(id, correlation, type, category, title, status,
                Overall.of(status == TaskStatus.COMPLETED ? 100 : null),
                ProgressStage.indeterminate(stage == null ? status.name() : stage, "根据业务记录恢复"),
                metrics, List.of(), summary, resource, error == null ? null : new ErrorInfo("BUSINESS_FAILURE", error),
                start, status.isTerminal() ? (finish == null ? LocalDateTime.now() : finish) : null,
                new Revision(epoch, 0));
    }
}
