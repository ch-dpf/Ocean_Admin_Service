package org.ocean.admin.gis.entity;

/** 数据集分类，统一维护分类编码与展示名称。 */
public enum GisDataSetCategory {
    IMAGERY(0L, "影像数据"),
    TERRAIN(1L, "地形数据"),
    VECTOR(2L, "矢量数据");

    private final Long categoryId;
    private final String categoryName;

    GisDataSetCategory(Long categoryId, String categoryName) {
        this.categoryId = categoryId;
        this.categoryName = categoryName;
    }

    public Long categoryId() {
        return categoryId;
    }

    public String categoryName() {
        return categoryName;
    }

    /** 判断编码是否属于当前支持的数据集分类。 */
    public static boolean isValid(Long categoryId) {
        for (GisDataSetCategory category : values()) {
            if (category.categoryId.equals(categoryId)) {
                return true;
            }
        }
        return false;
    }
}
