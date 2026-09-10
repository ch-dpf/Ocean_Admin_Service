package org.ocean.admin.platform.audit.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从 HTTP 请求中解析客户端真实 IP，供登录、审计日志等场景复用。
 * <p>
 * 优先读取反向代理透传头，避免在网关后仅得到内网地址。
 */
public final class RequestIpResolver {

    private RequestIpResolver() {
    }

    /**
     * 解析请求客户端 IP。
     * <p>
     * 优先级：{@code X-Forwarded-For} 首个有效地址 → {@code X-Real-IP} → {@code remoteAddr}。
     *
     * @param request HTTP 请求，可为 null
     * @return 客户端 IP；无法解析时返回 null
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = firstForwardedIp(request.getHeader("X-Forwarded-For"));
        if (forwarded != null) {
            return forwarded;
        }
        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        return normalize(request.getRemoteAddr());
    }

    /**
     * 从当前请求上下文解析客户端 IP。
     *
     * @return 客户端 IP；无请求上下文时返回 null
     */
    public static String resolveFromContext() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return resolve(attributes.getRequest());
    }

    /**
     * 优先使用已传入的 IP；为空时再从请求解析。
     *
     * @param preferred 调用方已提供的 IP，可为 null/空白
     * @param request   HTTP 请求
     * @return 最终 IP
     */
    public static String resolveOrFallback(String preferred, HttpServletRequest request) {
        String normalized = normalize(preferred);
        return normalized != null ? normalized : resolve(request);
    }

    private static String firstForwardedIp(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        for (String part : forwardedFor.split(",")) {
            String ip = normalize(part);
            if (ip != null) {
                return ip;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }
}
