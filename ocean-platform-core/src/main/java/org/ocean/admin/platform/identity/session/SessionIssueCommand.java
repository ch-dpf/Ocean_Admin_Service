package org.ocean.admin.platform.identity.session;

import java.time.Instant;
import java.util.UUID;

/** 创建认证会话所需的稳定上下文；敏感令牌原文由服务单独接收且不会持久化。 */
public record SessionIssueCommand(
        UUID userId,
        UUID platformId,
        UUID oauthClientId,
        String deviceId,
        String deviceName,
        String clientIp,
        String userAgent,
        Instant sessionExpiresAt,
        Instant refreshTokenExpiresAt) {
}
