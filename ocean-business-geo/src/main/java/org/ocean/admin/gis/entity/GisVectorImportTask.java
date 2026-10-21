package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 矢量导入的持久化任务及来源快照；version 用于租约隔离。 */
@Data
public class GisVectorImportTask {
    private Long id;
    private String taskNo;
    private String requestId;
    private Long sourceFileId;
    private Long dataSetId;
    private String sourceStorageKey;
    private String sourceSha256;
    private Long sourceSizeBytes;
    private Long sourceMetadataVersion;
    private String layerName;
    private String splitMode;
    private String taskStatus;
    private Long processedFeatureCount;
    private Long version;
    private LocalDateTime leaseExpiresAt;
    private Long errorFeatureIndex;
    private String errorMessage;
    private Long operatorId;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
