package org.ocean.admin.aspect;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationLogCommand;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 操作日志AOP切面
 * 采集显式声明了 {@link OperationLog} 的业务入口。
 *
 * @author DeepOcean
 * @since 2026-09-14
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
@Slf4j
public class OperationLogAspect {

    private static final int MAX_ERROR_LENGTH = 4000;

    // 参数序列化
    private final OperationLogSerializer serializer;
    // 日志分发
    private final OperationLogDispatcher dispatcher;

    @Around("@annotation(operationLog)")
    public Object record(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        // 记录开始时间
        long startedAt = System.nanoTime();
        Object response = null;
        Throwable failure = null;
        try {
            response = joinPoint.proceed();
            return response;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                // 执行时长
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                OperationLogCommand command = buildCommand(
                        joinPoint, operationLog, response, failure, elapsedMillis);
                // 分发投递操作日志信息
                dispatcher.dispatch(command, operationLog.async());
            } catch (RuntimeException auditFailure) {
                log.error("操作日志采集失败: method={}", joinPoint.getSignature().toShortString(), auditFailure);
            }
        }
    }

    private OperationLogCommand buildCommand(
            ProceedingJoinPoint joinPoint,
            OperationLog operationLog,
            Object response,
            Throwable failure,
            long elapsedMillis) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        HttpServletRequest request = currentRequest();
        CurrentOperator operator = currentOperator(request);
        boolean success = failure == null && isSuccessfulResponse(response);
        String errorMessage = failure == null
                ? responseErrorMessage(response)
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();

        return new OperationLogCommand(
                operator == null ? null : operator.userId(),
                operator == null ? null : operator.username(),
                operationLog.module(),
                operationLog.type(),
                operationLog.description(),
                signature.getDeclaringTypeName() + "." + signature.getName(),
                request == null ? null : request.getRequestURI(),
                request == null ? null : request.getMethod(),
                operationLog.recordRequest()
                        ? serializer.serializeRequest(signature.getParameterNames(), joinPoint.getArgs(),
                                operationLog.excludeFields())
                        : null,
                operationLog.recordResponse()
                        ? serializer.serializeResponse(response, operationLog.excludeFields())
                        : null,
                resolveClientIp(request),
                request == null ? null : truncate(request.getHeader("User-Agent"), 500),
                elapsedMillis,
                success,
                success ? null : truncate(errorMessage, MAX_ERROR_LENGTH),
                LocalDateTime.now());
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private CurrentOperator currentOperator(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(CurrentOperator.REQUEST_ATTRIBUTE);
        return value instanceof CurrentOperator operator ? operator : null;
    }

    private boolean isSuccessfulResponse(Object response) {
        return !(response instanceof ResponseResult<?> result)
                || result.getCode() != null && result.getCode() >= 200 && result.getCode() < 300;
    }

    private String responseErrorMessage(Object response) {
        if (response instanceof ResponseResult<?> result && !isSuccessfulResponse(result)) {
            return result.getMessage();
        }
        return null;
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String first = forwardedFor.split(",", 2)[0].trim();
            if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) {
                return truncate(first, 50);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank() && !"unknown".equalsIgnoreCase(realIp)) {
            return truncate(realIp.trim(), 50);
        }
        return truncate(request.getRemoteAddr(), 50);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
