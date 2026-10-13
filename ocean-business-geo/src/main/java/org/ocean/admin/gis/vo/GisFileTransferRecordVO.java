package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** GIS 原始文件上传、下载传输记录响应对象。 */
@Data
public class GisFileTransferRecordVO {

    private Long id;
    private String recordNo;
    private String requestId;
    private String operationType;
    private Long dataSetId;
    private String dataSetName;
    private Long categoryId;
    private Integer totalCount;
    private Integer completedCount;
    private Integer failedCount;
    private Long totalBytes;
    private Long uploadedBytes;

    @Schema(description = "QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED 或 FAILED")
    private String recordStatus;

    private String currentStage;
    private String resultStorageKey;
    private String errorMessage;
    private Long operatorId;
    private Long version;
    private Integer progress;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @Schema(description = "记录详情中的逐文件历史快照；分页列表不返回")
    private List<GisFileMetaVO> files;
}
