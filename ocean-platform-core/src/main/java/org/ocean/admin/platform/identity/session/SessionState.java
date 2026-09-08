package org.ocean.admin.platform.identity.session;

import java.time.Instant;
import java.util.UUID;

/** 可缓存的活跃会话投影；PostgreSQL 记录仍是唯一权威源。 */
public record SessionState(
        UUID sessionId,
        UUID tokenFamilyId,
        long version,
        Instant sessionExpiresAt,
        Instant refreshTokenExpiresAt) {
}
