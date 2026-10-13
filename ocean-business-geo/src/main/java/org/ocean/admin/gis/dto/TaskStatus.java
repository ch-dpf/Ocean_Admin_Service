package org.ocean.admin.gis.dto;

/** 处理任务、文件传输任务与实时进度共用的任务状态。 */
public enum TaskStatus {
    QUEUED, // 等待运行
    RUNNING, // 正在运行
    COMPLETED, // 完成
    PARTIAL_FAILED, // 部分失败
    FAILED; // 失败

    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }
}
