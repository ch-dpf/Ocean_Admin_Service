package org.ocean.admin.platform.audit.adapter;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.audit.OperationLogCommand;
import org.ocean.admin.kernel.audit.OperationLogSink;
import org.ocean.admin.platform.audit.entity.SysOperationLog;
import org.ocean.admin.platform.audit.mapper.SysOperationLogMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将 kernel 定义的操作日志命令持久化到平台审计表。
 */
@Component
@RequiredArgsConstructor
public class DatabaseOperationLogSink implements OperationLogSink {

    private final SysOperationLogMapper operationLogMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(OperationLogCommand command) {
        SysOperationLog entity = new SysOperationLog();
        entity.setUserId(command.userId());
        entity.setUsername(truncate(command.username(), 50));
        entity.setModule(truncate(command.module(), 50));
        entity.setOperationType(truncate(command.operationType().name(), 20));
        entity.setDescription(truncate(command.description(), 500));
        entity.setMethod(truncate(command.method(), 200));
        entity.setRequestUrl(truncate(command.requestUrl(), 500));
        entity.setRequestMethod(truncate(command.requestMethod(), 10));
        entity.setRequestParams(command.requestParams());
        entity.setResponseResult(command.responseResult());
        entity.setIpAddress(truncate(command.ipAddress(), 50));
        entity.setUserAgent(truncate(command.userAgent(), 500));
        entity.setExecutionTime(command.executionTime());
        entity.setStatus(command.success() ? 1 : 0);
        entity.setErrorMessage(command.errorMessage());
        entity.setCreateTime(command.occurredAt());
        entity.setDeleted(0);
        operationLogMapper.insert(entity);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
