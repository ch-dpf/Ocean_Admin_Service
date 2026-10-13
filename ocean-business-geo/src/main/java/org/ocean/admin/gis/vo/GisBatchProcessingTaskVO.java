package org.ocean.admin.gis.vo;

import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingType;

/** 多文件处理任务提交结果。 */
@Data
@Builder
public class GisBatchProcessingTaskVO {
    private Long taskId;
    private Long tileSetId;
    private String taskNo;
    private GisInputSourceType sourceType;
    private String sourceTypeName;
    private GisProcessingType processingType;
    private String outputKey;
    private Long totalCount;
    private TaskStatus taskStatus;
}
