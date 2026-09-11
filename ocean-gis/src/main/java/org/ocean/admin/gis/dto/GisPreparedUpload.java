package org.ocean.admin.gis.dto;

import java.util.List;

/**
 * 已完成暂存和数据库建档、可以交给异步线程处理的上传任务。
 */
public record GisPreparedUpload(
        Long taskId,
        String taskNo,
        Long dataSetId,
        String dataSetCode,
        List<GisUploadFileItem> files) {
}
