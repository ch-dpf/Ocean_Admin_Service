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
public class GisImportTaskVO {
    private Long recordId;
    private String recordNo;
    private Integer acceptedCount;
    private Integer failedCount;

    /** @deprecated 新导入不再创建 gis_task。 */
    @Deprecated
    private Long taskId;

    /** @deprecated 请使用 recordNo。 */
    @Deprecated
    private String taskNo;
    private Long dataSetId;
    private Integer totalCount;
    private String status;
}
