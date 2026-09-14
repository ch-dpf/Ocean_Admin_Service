package org.ocean.admin.kernel.audit;

import java.time.LocalDateTime;

/**
 * 与 Web、ORM 无关的操作日志写入命令。
 * 一条完整的待写入日志
 */
public record OperationLogCommand(
        // 用户id
        Long userId,
        // 用户名称
        String username,
        // 业务模块编码
        String module,
        // 操作类型
        OperationType operationType,
        // 操作说明
        String description,
        // 方法名称
        String method,
        // 请求url
        String requestUrl,
        // 请求方法
        String requestMethod,
        // 请求参数
        String requestParams,
        // 响应结果
        String responseResult,
        // ip地址
        String ipAddress,
        // 客户端信息-用户代理
        String userAgent,
        // 执行时长
        long executionTime,
        // 是否成功
        boolean success,
        // 错误信息
        String errorMessage,
        // 发生时间
        LocalDateTime occurredAt) {
}
