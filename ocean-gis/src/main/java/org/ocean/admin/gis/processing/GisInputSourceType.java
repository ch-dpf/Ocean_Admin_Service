package org.ocean.admin.gis.processing;

/** 处理任务允许的三种输入来源。 */
public enum GisInputSourceType {
    UPLOAD("文件上传"),
    WORKSPACE("工作空间"),
    MANAGED_FILE("已管理文件");

    private final String displayName;

    GisInputSourceType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
