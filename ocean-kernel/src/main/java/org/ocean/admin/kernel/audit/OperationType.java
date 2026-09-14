package org.ocean.admin.kernel.audit;

/**
 * 可审计的业务操作类型。
 * 增、删、改、查、导出、导入、提交、取消
 */
public enum OperationType {
    INSERT,
    UPDATE,
    DELETE,
    QUERY,
    EXPORT,
    IMPORT,
    SUBMIT,
    CANCEL
}
