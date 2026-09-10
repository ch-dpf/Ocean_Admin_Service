package org.ocean.admin.platform.identity.event;

import java.time.LocalDateTime;

/**
 * 用户登出事件。
 */
public record UserLogoutEvent(String sessionId, LocalDateTime occurredAt) {
}
