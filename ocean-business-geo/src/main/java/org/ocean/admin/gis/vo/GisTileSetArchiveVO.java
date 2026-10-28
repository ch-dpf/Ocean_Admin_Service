package org.ocean.admin.gis.vo;

import org.ocean.admin.gis.progress.ProgressSnapshot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
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
    private Integer packingPercent;
    private Boolean downloadAvailable;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
