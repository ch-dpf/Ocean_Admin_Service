package org.ocean.admin.gis.processing;

/** 单文件切片处理类型，同时与 GIS 数据集类别保持一一对应。 */
public enum GisProcessingType {
    IMAGERY(0L, "影像处理"),
    TERRAIN(1L, "地形处理"),
    VECTOR(2L, "矢量处理");

    private final Long categoryId;
    private final String displayName;

    GisProcessingType(Long categoryId, String displayName) {
        this.categoryId = categoryId;
        this.displayName = displayName;
    }

    public Long categoryId() {
        return categoryId;
    }

    public String displayName() {
        return displayName;
    }
}
