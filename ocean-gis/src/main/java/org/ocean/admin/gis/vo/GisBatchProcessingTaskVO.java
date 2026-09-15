package org.ocean.admin.gis.vo;

import lombok.Builder;
import lombok.Data;

/** 多文件处理任务提交结果。 */
@Data
@Builder
public class GisBatchProcessingTaskVO {
    private Long taskId;
    private String taskNo;
    private String processingType;
    private String outputKey;
    private Integer totalCount;
    private String status;
}
