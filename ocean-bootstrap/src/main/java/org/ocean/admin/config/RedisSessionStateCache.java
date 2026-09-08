package org.ocean.admin.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.ocean.admin.platform.identity.session.SessionState;
import org.ocean.admin.platform.identity.session.SessionStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 活跃会话投影。所有故障均退化为未命中，不改变 PostgreSQL 权威状态。 */
final class RedisSessionStateCache implements SessionStateCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStateCache.class);

    private final StringRedisTemplate redisTemplate;
    private final SessionCacheProperties properties;
    private final Clock clock;

    RedisSessionStateCache(
            StringRedisTemplate redisTemplate,
            SessionCacheProperties properties,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<SessionState> find(UUID sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(key(sessionId));
            if (value == null) {
                return Optional.empty();
            }
            SessionState state = decode(sessionId, value);
            Instant now = clock.instant();
            if (!state.sessionExpiresAt().isAfter(now)
                    || !state.refreshTokenExpiresAt().isAfter(now)) {
                evict(sessionId);
                return Optional.empty();
            }
            return Optional.of(state);
        } catch (RuntimeException failure) {
            log.warn("Redis session cache read failed for session {}; falling back to database",
                    sessionId, failure);
            return Optional.empty();
        }
    }

    @Override
    public void put(SessionState session) {
        try {
            Duration ttl = ttl(session);
            if (ttl.isZero() || ttl.isNegative()) {
                evict(session.sessionId());
                return;
            }
            redisTemplate.opsForValue().set(key(session.sessionId()), encode(session), ttl);
        } catch (RuntimeException failure) {
            log.warn("Redis session cache write failed for session {}; database state is unchanged",
                    session.sessionId(), failure);
        }
    }

    @Override
    public void evict(UUID sessionId) {
        try {
            redisTemplate.delete(key(sessionId));
        } catch (RuntimeException failure) {
            log.warn("Redis session cache eviction failed for session {}; entry will expire by TTL",
                    sessionId, failure);
        }
    }

    private Duration ttl(SessionState session) {
        Instant now = clock.instant();
        Duration untilSessionExpiry = Duration.between(now, session.sessionExpiresAt());
        Duration untilRefreshExpiry = Duration.between(now, session.refreshTokenExpiresAt());
        return minimum(properties.getMaximumTtl(), untilSessionExpiry, untilRefreshExpiry);
    }

    private static Duration minimum(Duration first, Duration second, Duration third) {
        Duration result = first.compareTo(second) <= 0 ? first : second;
        return result.compareTo(third) <= 0 ? result : third;
    }

    private String key(UUID sessionId) {
        return properties.getKeyPrefix() + sessionId;
    }

    private static String encode(SessionState state) {
        return state.tokenFamilyId() + "|" + state.version() + "|"
                + state.sessionExpiresAt().toEpochMilli() + "|"
                + state.refreshTokenExpiresAt().toEpochMilli();
    }

    private static SessionState decode(UUID sessionId, String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid cached session state");
        }
        return new SessionState(
                sessionId,
                UUID.fromString(parts[0]),
                Long.parseLong(parts[1]),
                Instant.ofEpochMilli(Long.parseLong(parts[2])),
                Instant.ofEpochMilli(Long.parseLong(parts[3])));
    }
}
