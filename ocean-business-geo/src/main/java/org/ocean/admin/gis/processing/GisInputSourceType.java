package org.ocean.admin.gis.processing;

/** 处理任务支持的输入来源编码，名称由 JSON 字典维护。 */
public enum GisInputSourceType {
    UPLOAD,
    WORKSPACE,
    MANAGED_FILE,
    VECTOR_RESOURCE
}
