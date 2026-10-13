package org.ocean.admin.gis.dto;

/** 实时任务状态。 */
public enum TaskStatus {
    RUNNING, // 正在运行
    COMPLETED, // 完成
    PARTIAL_FAILED, // 部分失败
    FAILED; // 失败

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
