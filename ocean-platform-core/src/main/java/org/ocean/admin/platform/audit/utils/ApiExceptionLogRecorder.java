package org.ocean.admin.platform.audit.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.platform.audit.entity.SysExceptionLog;
import org.ocean.admin.platform.audit.service.SysExceptionLogService;
import org.ocean.admin.platform.identity.utils.JwtUtil;
import org.springframework.stereotype.Component;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiExceptionLogRecorder {

    public static final String REQUEST_LOGGED_FLAG = "__api_exception_logged__";
    private static final long DEDUP_WINDOW_MILLIS = 30_000L;

    private static final ConcurrentHashMap<String, Long> FINGERPRINT_CACHE = new ConcurrentHashMap<>();

    private final SysExceptionLogService exceptionLogService;
    private final JwtUtil jwtUtil;

    public void record(HttpServletRequest request, Throwable throwable, String requestParamsOverride) {
        if (request == null || throwable == null) {
            return;
        }
        if (Boolean.TRUE.equals(request.getAttribute(REQUEST_LOGGED_FLAG))) {
            return;
        }
        request.setAttribute(REQUEST_LOGGED_FLAG, true);

        try {
            String fingerprint = buildFingerprint(request, throwable);
            long now = System.currentTimeMillis();
            Long lastLoggedAt = FINGERPRINT_CACHE.get(fingerprint);
            if (lastLoggedAt != null && now - lastLoggedAt < DEDUP_WINDOW_MILLIS) {
                return;
            }
            FINGERPRINT_CACHE.put(fingerprint, now);
            cleanupExpiredFingerprints(now);

            SysExceptionLog entity = new SysExceptionLog();
            entity.setExceptionType(throwable.getClass().getName());
            entity.setExceptionMessage(truncate(throwable.getMessage(), 500));
            entity.setRequestUrl(request.getRequestURI());
            entity.setRequestMethod(request.getMethod());
            entity.setRequestParams(resolveRequestParams(request, requestParamsOverride));
            entity.setIpAddress(RequestIpResolver.resolve(request));
            entity.setStatus(0);
            entity.setCreateTime(LocalDateTime.now());

            String token = resolveBearerToken(request.getHeader("Authorization"));
            if (token != null && jwtUtil.validateToken(token)) {
                entity.setUserId(jwtUtil.getUserIdFromToken(token));
                entity.setUsername(jwtUtil.getUsernameFromToken(token));
            }

            StackTraceElement[] stack = throwable.getStackTrace();
            StackTraceElement top = stack != null && stack.length > 0 ? stack[0] : null;
            if (top != null) {
                entity.setClassMethod(top.getClassName() + "." + top.getMethodName());
                entity.setLineNumber(top.getLineNumber());
            }
            entity.setStackTrace(truncate(toStackTrace(throwable), 12000));
            exceptionLogService.recordExceptionLog(entity);
        } catch (Exception ex) {
            log.error("记录系统异常日志失败: {}", ex.getMessage(), ex);
        }
    }

    private String resolveRequestParams(HttpServletRequest request, String override) {
        if (override != null && !override.isBlank()) {
            return truncate(override, 2000);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (request.getQueryString() != null) {
            params.put("queryString", request.getQueryString());
        }
        if (request.getParameterMap() != null && !request.getParameterMap().isEmpty()) {
            params.put("parameters", request.getParameterMap().entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> Arrays.toString(e.getValue()),
                    (a, b) -> a,
                    LinkedHashMap::new
            )));
        }
        return truncate(params.toString(), 2000);
    }

    private String resolveBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7);
    }

    private String toStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private String buildFingerprint(HttpServletRequest request, Throwable throwable) {
        StackTraceElement top = throwable.getStackTrace() != null && throwable.getStackTrace().length > 0
                ? throwable.getStackTrace()[0]
                : null;
        String topLocation = top == null ? "-" : (top.getClassName() + ":" + top.getMethodName() + ":" + top.getLineNumber());
        String message = truncate(throwable.getMessage(), 300);
        return String.join("|",
                request.getMethod(),
                request.getRequestURI(),
                throwable.getClass().getName(),
                String.valueOf(message),
                topLocation
        );
    }

    private void cleanupExpiredFingerprints(long now) {
        FINGERPRINT_CACHE.entrySet().removeIf(entry -> now - entry.getValue() > DEDUP_WINDOW_MILLIS);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

