package org.ocean.admin.gis.vo;

/** 多文件处理任务中的单文件结果。 */
public record GisProcessingTaskFileVO(
        Long id,
        Integer fileIndex,
        String originalName,
        String outputKey,
        String status,
        String errorMessage) {
}
