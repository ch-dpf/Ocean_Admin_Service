package org.ocean.admin.aspect;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.audit.OperationLogCommand;
import org.ocean.admin.kernel.audit.OperationLogSink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/** 将采集到的日志投递给持久化端口。 */
@Slf4j
@Component
public class OperationLogDispatcher {

    private final OperationLogSink sink;
    private final Executor executor;
    private final boolean asyncEnabled;

    public OperationLogDispatcher(
            OperationLogSink sink,
            @Qualifier("operationLogExecutor") Executor executor,
            @Value("${ocean.audit.operation-log.async-enabled:true}") boolean asyncEnabled) {
        this.sink = sink;
        this.executor = executor;
        this.asyncEnabled = asyncEnabled;
    }

    public void dispatch(OperationLogCommand command, boolean asyncRequested) {
        if (!asyncEnabled || !asyncRequested) {
            writeSafely(command);
            return;
        }
        try {
            executor.execute(() -> writeSafely(command));
        } catch (RuntimeException ex) {
            log.warn("操作日志异步投递失败，降级为当前线程写入: {}", ex.getMessage());
            writeSafely(command);
        }
    }

    private void writeSafely(OperationLogCommand command) {
        try {
            sink.write(command);
        } catch (Exception ex) {
            log.error("操作日志写入失败: module={}, type={}, method={}",
                    command.module(), command.operationType(), command.method(), ex);
        }
    }
}
