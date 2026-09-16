package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** GIS 文件导入导出记录响应对象。 */
@Data
public class GisFileOptRecordVO {

    private Long id;
    private String recordNo;
    private String operationType;
    private Long dataSetId;
    private String dataSetName;
    private Long categoryId;
    private Integer totalCount;
    private Integer completedCount;
    private Integer failedCount;

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

    @Schema(description = "记录详情中的逐文件结果；分页列表不返回")
    private List<GisFileMetaVO> files;
}
