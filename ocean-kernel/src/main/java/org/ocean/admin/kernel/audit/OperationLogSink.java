package org.ocean.admin.kernel.audit;

/**
 * 操作日志输出端口。具体存储由上层模块实现。
 * 定义日志存储端口
 */
@FunctionalInterface
public interface OperationLogSink {

    void write(OperationLogCommand command);
}
