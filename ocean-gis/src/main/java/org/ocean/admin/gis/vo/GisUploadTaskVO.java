package org.ocean.admin.gis.vo;

import lombok.Builder;
import lombok.Data;

/**
 * gis上传任务VO
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Data
@Builder
public class GisUploadTaskVO {
    private Long taskId;
    private String taskNo;
    private Long dataSetId;
    private Integer totalCount;
    private String status;
}
