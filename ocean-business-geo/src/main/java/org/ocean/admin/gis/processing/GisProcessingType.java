package org.ocean.admin.gis.processing;

/** 单文件切片处理类型，同时与 GIS 数据集类别保持一一对应。 */
public enum GisProcessingType {
    IMAGERY(0L),
    TERRAIN(1L),
    VECTOR(2L);

    private final Long categoryId;

    GisProcessingType(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long categoryId() {
        return categoryId;
    }

}
