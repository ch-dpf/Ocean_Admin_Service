package org.ocean.admin.gis.vo;

import org.ocean.admin.gis.progress.ProgressSnapshot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;

/** 静态瓦片集归档的列表与详情响应。 */
@Data
public class GisTileSetArchiveVO {
    private ProgressSnapshot.Reference progress;
    private Long id;
    private String archiveNo;
    private Long processingTaskId;
    private String taskNo;
    private String taskName;
    private GisProcessingType processingType;
    private Long tileSetId;
    private String archiveName;
    private String archiveFormat;

    @Schema(description = "QUEUED、RUNNING、COMPLETED 或 FAILED")
    private TaskStatus archiveStatus;

    /** 仅供查询投影校验缓存代次，不作为接口响应字段。 */
    @Getter(AccessLevel.NONE)
    private Long progressEpoch;

    private String currentStage;
    private Long totalFileCount;
    private Long processedFileCount;
    private Long totalBytes;
    private Long processedBytes;
    private Long archiveSizeBytes;
    private String sha256;
    private Long operatorId;
    private Long downloadCount;
    private LocalDateTime lastDownloadTime;
    private String errorMessage;
    private Long version;
    @Schema(description = "打包阶段百分比；实时快照或工作量总量不可用时为null")
    private Integer packingPercent;
    private Boolean downloadAvailable;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 业务状态以数据库为准，中间阶段和工作量只从同一代次的缓存补充。 */
    public void applyProgress(ProgressSnapshot snapshot) {
        if (archiveStatus == TaskStatus.COMPLETED) {
            packingPercent = 100;
            return;
        }
        currentStage = archiveStatus.name();
        totalFileCount = null;
        processedFileCount = null;
        totalBytes = null;
        processedBytes = null;
        packingPercent = null;
        if (snapshot == null || progressEpoch == null || snapshot.revision().epoch() != progressEpoch
                || snapshot.status() != archiveStatus) return;
        currentStage = snapshot.stage().code();
        for (ProgressSnapshot.Metric metric : snapshot.metrics()) {
            if ("PACKED_FILES".equals(metric.code())) {
                totalFileCount = metric.total();
                processedFileCount = metric.value();
            } else if ("PACKED_BYTES".equals(metric.code())) {
                totalBytes = metric.total();
                processedBytes = metric.value();
            }
        }
        packingPercent = snapshot.metricPercent("PACKED_BYTES", "PACKED_FILES");
    }
}
