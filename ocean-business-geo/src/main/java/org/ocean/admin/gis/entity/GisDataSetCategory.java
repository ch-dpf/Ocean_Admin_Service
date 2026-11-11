package org.ocean.admin.gis.entity;

/** 数据集分类的可执行编码，名称和展示顺序由 JSON 字典维护。 */
public enum GisDataSetCategory {
    IMAGERY(0L),
    TERRAIN(1L),
    VECTOR(2L);

    private final Long categoryId;

    GisDataSetCategory(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long categoryId() {
        return categoryId;
    }
}
