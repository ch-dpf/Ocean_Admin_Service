package org.ocean.admin.platform.identity.event;

import java.time.LocalDateTime;

/**
 * 用户登录结果事件。身份域只发布事实，由审计域负责持久化。
 */
public record UserLoginEvent(
        Long userId,
        String username,
        boolean success,
        String message,
        String sessionId,
        String platform,
        String deviceId,
        String browser,
        String os,
        String userAgent,
        String ipAddress,
        LocalDateTime occurredAt) {
}
