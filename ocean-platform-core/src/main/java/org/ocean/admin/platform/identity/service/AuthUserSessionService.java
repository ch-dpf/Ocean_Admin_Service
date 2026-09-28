package org.ocean.admin.platform.identity.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis 的用户并发登录会话服务。
 * 会话采用 30 分钟滑动空闲过期，且始终受 JWT 绝对过期时间限制。
 */
@Service
@RequiredArgsConstructor
public class AuthUserSessionService {

    private static final long IDLE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final String USER_SESSIONS_KEY = "ocean-admin:auth:{%d}:sessions";
    private static final String SESSION_DEVICE_KEY = "ocean-admin:auth:{%d}:session-devices";
    private static final String SESSION_ABSOLUTE_EXPIRE_KEY = "ocean-admin:auth:{%d}:session-absolute-expires";
    private static final String SESSION_LAST_ACTIVE_KEY = "ocean-admin:auth:{%d}:session-last-active";

    private static final DefaultRedisScript<String> REGISTER_SESSION_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local maxSessions = tonumber(ARGV[2])
            local forceLogin = ARGV[3] == '1'
            local sessionId = ARGV[4]
            local deviceId = ARGV[5]
            local absoluteExpireAt = tonumber(ARGV[6])
            local effectiveExpireAt = tonumber(ARGV[7])
            local replaceSessionId = ARGV[8]

            local function removeSession(target)
                if target and target ~= '' then
                    redis.call('ZREM', KEYS[1], target)
                    redis.call('HDEL', KEYS[2], target)
                    redis.call('HDEL', KEYS[3], target)
                    redis.call('HDEL', KEYS[4], target)
                end
            end

            local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now)
            for _, expiredSessionId in ipairs(expired) do
                removeSession(expiredSessionId)
            end

            local members = redis.call('ZRANGE', KEYS[1], 0, -1)
            for _, member in ipairs(members) do
                local absolute = redis.call('HGET', KEYS[3], member)
                local lastActive = redis.call('HGET', KEYS[4], member)
                if not absolute or not lastActive or tonumber(absolute) <= now then
                    removeSession(member)
                end
            end

            local replaced = ''
            local replacementExists = false
            if replaceSessionId and replaceSessionId ~= '' and replaceSessionId ~= sessionId
                    and redis.call('ZSCORE', KEYS[1], replaceSessionId) then
                replacementExists = true
            end

            local count = redis.call('ZCARD', KEYS[1])
            local effectiveCount = count
            if replacementExists then
                effectiveCount = effectiveCount - 1
            end
            local evicted = ''
            if effectiveCount >= maxSessions and not forceLogin then
                return 'LIMIT|' .. count .. '||'
            end

            if replacementExists then
                removeSession(replaceSessionId)
                replaced = replaceSessionId
                count = count - 1
            end

            if count >= maxSessions then
                if not forceLogin then
                    return 'LIMIT|' .. count .. '||'
                end
                local candidates = redis.call('ZRANGE', KEYS[1], 0, -1)
                local oldestLastActive = nil
                for _, candidate in ipairs(candidates) do
                    local candidateLastActive = tonumber(redis.call('HGET', KEYS[4], candidate))
                    if candidateLastActive and (not oldestLastActive or candidateLastActive < oldestLastActive) then
                        oldestLastActive = candidateLastActive
                        evicted = candidate
                    end
                end
                if evicted ~= '' then
                    removeSession(evicted)
                end
            end

            redis.call('ZADD', KEYS[1], effectiveExpireAt, sessionId)
            redis.call('HSET', KEYS[2], sessionId, deviceId)
            redis.call('HSET', KEYS[3], sessionId, absoluteExpireAt)
            redis.call('HSET', KEYS[4], sessionId, now)

            local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            if #latest == 2 then
                local keyExpireAt = tonumber(latest[2])
                for index = 1, #KEYS do
                    redis.call('PEXPIREAT', KEYS[index], keyExpireAt)
                end
            end
            count = redis.call('ZCARD', KEYS[1])
            return 'OK|' .. count .. '|' .. evicted .. '|' .. replaced
            """, String.class);

    private static final DefaultRedisScript<Long> TOUCH_SESSION_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local idleTimeout = tonumber(ARGV[2])
            local tokenAbsoluteExpireAt = tonumber(ARGV[3])
            local sessionId = ARGV[4]

            local function removeSession()
                redis.call('ZREM', KEYS[1], sessionId)
                redis.call('HDEL', KEYS[2], sessionId)
                redis.call('HDEL', KEYS[3], sessionId)
                redis.call('HDEL', KEYS[4], sessionId)
            end

            local currentExpireAt = redis.call('ZSCORE', KEYS[1], sessionId)
            local storedAbsoluteExpireAt = redis.call('HGET', KEYS[3], sessionId)
            if not currentExpireAt or not storedAbsoluteExpireAt
                    or tonumber(currentExpireAt) <= now or tonumber(storedAbsoluteExpireAt) <= now
                    or tokenAbsoluteExpireAt <= now then
                removeSession()
                return 0
            end

            local absoluteExpireAt = math.min(tonumber(storedAbsoluteExpireAt), tokenAbsoluteExpireAt)
            local effectiveExpireAt = math.min(now + idleTimeout, absoluteExpireAt)
            redis.call('ZADD', KEYS[1], effectiveExpireAt, sessionId)
            redis.call('HSET', KEYS[3], sessionId, absoluteExpireAt)
            redis.call('HSET', KEYS[4], sessionId, now)

            local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            if #latest == 2 then
                local keyExpireAt = tonumber(latest[2])
                for index = 1, #KEYS do
                    redis.call('PEXPIREAT', KEYS[index], keyExpireAt)
                end
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> COUNT_SESSIONS_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local function removeSession(target)
                redis.call('ZREM', KEYS[1], target)
                redis.call('HDEL', KEYS[2], target)
                redis.call('HDEL', KEYS[3], target)
                redis.call('HDEL', KEYS[4], target)
            end

            local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now)
            for _, expiredSessionId in ipairs(expired) do
                removeSession(expiredSessionId)
            end

            local members = redis.call('ZRANGE', KEYS[1], 0, -1)
            for _, member in ipairs(members) do
                local absolute = redis.call('HGET', KEYS[3], member)
                local lastActive = redis.call('HGET', KEYS[4], member)
                if not absolute or not lastActive or tonumber(absolute) <= now then
                    removeSession(member)
                end
            end

            local count = redis.call('ZCARD', KEYS[1])
            if count == 0 then
                for index = 1, #KEYS do
                    redis.call('DEL', KEYS[index])
                end
            end
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> REMOVE_SESSION_SCRIPT = new DefaultRedisScript<>("""
            local sessionId = ARGV[1]
            local removed = redis.call('ZREM', KEYS[1], sessionId)
            redis.call('HDEL', KEYS[2], sessionId)
            redis.call('HDEL', KEYS[3], sessionId)
            redis.call('HDEL', KEYS[4], sessionId)
            if redis.call('ZCARD', KEYS[1]) == 0 then
                for index = 1, #KEYS do
                    redis.call('DEL', KEYS[index])
                end
            end
            return removed
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RegistrationResult registerSession(Long userId,
                                              String deviceId,
                                              String sessionId,
                                              long absoluteExpireAtMillis,
                                              int maxConcurrentSessions,
                                              boolean forceLogin,
                                              String replaceSessionId) {
        long now = System.currentTimeMillis();
        long effectiveExpireAt = Math.min(now + IDLE_TIMEOUT_MILLIS, absoluteExpireAtMillis);
        if (effectiveExpireAt <= now) {
            throw new IllegalArgumentException("JWT已过期，无法创建登录会话");
        }

        int safeMaxSessions = Math.max(1, maxConcurrentSessions);
        String rawResult = redisTemplate.execute(
                REGISTER_SESSION_SCRIPT,
                sessionKeys(userId),
                String.valueOf(now),
                String.valueOf(safeMaxSessions),
                forceLogin ? "1" : "0",
                sessionId,
                normalizeDeviceId(deviceId),
                String.valueOf(absoluteExpireAtMillis),
                String.valueOf(effectiveExpireAt),
                replaceSessionId == null ? "" : replaceSessionId
        );
        if (rawResult == null || rawResult.isBlank()) {
            throw new IllegalStateException("Redis会话注册未返回结果");
        }

        String[] values = rawResult.split("\\|", -1);
        int activeSessionCount = values.length > 1 ? Integer.parseInt(values[1]) : 0;
        if ("LIMIT".equals(values[0])) {
            throw new ConcurrentSessionLimitException(safeMaxSessions, activeSessionCount);
        }
        if (!"OK".equals(values[0])) {
            throw new IllegalStateException("Redis会话注册结果无效: " + rawResult);
        }
        return new RegistrationResult(
                activeSessionCount,
                emptyToNull(values.length > 2 ? values[2] : null),
                emptyToNull(values.length > 3 ? values[3] : null)
        );
    }

    public int countActiveSessions(Long userId) {
        Long count = redisTemplate.execute(
                COUNT_SESSIONS_SCRIPT,
                sessionKeys(userId),
                String.valueOf(System.currentTimeMillis())
        );
        return count == null ? 0 : count.intValue();
    }

    public boolean isSessionActive(Long userId, String sessionId, long jwtAbsoluteExpireAtMillis) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Long active = redisTemplate.execute(
                TOUCH_SESSION_SCRIPT,
                sessionKeys(userId),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(IDLE_TIMEOUT_MILLIS),
                String.valueOf(jwtAbsoluteExpireAtMillis),
                sessionId
        );
        return active != null && active == 1L;
    }

    public boolean isSessionOwned(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Double expireAt = redisTemplate.opsForZSet().score(buildUserSessionsKey(userId), sessionId);
        Object absoluteExpireAt = redisTemplate.opsForHash().get(buildSessionAbsoluteExpireKey(userId), sessionId);
        if (expireAt == null || absoluteExpireAt == null) {
            return false;
        }
        try {
            long now = System.currentTimeMillis();
            return expireAt.longValue() > now && Long.parseLong(String.valueOf(absoluteExpireAt)) > now;
        } catch (NumberFormatException ex) {
            removeSession(userId, sessionId);
            return false;
        }
    }

    public String findDeviceIdBySession(Long userId, String sessionId) {
        if (!isSessionOwned(userId, sessionId)) {
            return null;
        }
        Object deviceId = redisTemplate.opsForHash().get(buildSessionDeviceKey(userId), sessionId);
        return deviceId == null ? null : String.valueOf(deviceId);
    }

    public void removeSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate.execute(REMOVE_SESSION_SCRIPT, sessionKeys(userId), sessionId);
    }

    public List<ActiveSession> listActiveSessions(Long userId) {
        countActiveSessions(userId);
        Set<ZSetOperations.TypedTuple<String>> sessions = redisTemplate.opsForZSet()
                .rangeWithScores(buildUserSessionsKey(userId), 0, -1);
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        List<ActiveSession> result = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> session : sessions) {
            String sessionId = session.getValue();
            Double expireAt = session.getScore();
            if (sessionId == null || expireAt == null || expireAt.longValue() <= now) {
                continue;
            }
            Object deviceId = redisTemplate.opsForHash().get(buildSessionDeviceKey(userId), sessionId);
            Object lastActiveAt = redisTemplate.opsForHash().get(buildSessionLastActiveKey(userId), sessionId);
            result.add(new ActiveSession(
                    sessionId,
                    deviceId == null ? null : String.valueOf(deviceId),
                    Math.max(0L, (expireAt.longValue() - now) / 1000L),
                    toLocalDateTime(lastActiveAt)
            ));
        }
        return result;
    }

    public long getIdleTimeoutSeconds() {
        return Duration.ofMillis(IDLE_TIMEOUT_MILLIS).toSeconds();
    }

    private List<String> sessionKeys(Long userId) {
        return List.of(
                buildUserSessionsKey(userId),
                buildSessionDeviceKey(userId),
                buildSessionAbsoluteExpireKey(userId),
                buildSessionLastActiveKey(userId)
        );
    }

    private String buildUserSessionsKey(Long userId) {
        return String.format(USER_SESSIONS_KEY, userId);
    }

    private String buildSessionDeviceKey(Long userId) {
        return String.format(SESSION_DEVICE_KEY, userId);
    }

    private String buildSessionAbsoluteExpireKey(Long userId) {
        return String.format(SESSION_ABSOLUTE_EXPIRE_KEY, userId);
    }

    private String buildSessionLastActiveKey(Long userId) {
        return String.format(SESSION_LAST_ACTIVE_KEY, userId);
    }

    private String normalizeDeviceId(String deviceId) {
        return deviceId == null || deviceId.isBlank() ? "unknown-client" : deviceId.trim();
    }

    private LocalDateTime toLocalDateTime(Object epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(String.valueOf(epochMillis))),
                    ZoneId.systemDefault()
            );
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Getter
    @AllArgsConstructor
    public static class RegistrationResult {
        private final int activeSessionCount;
        private final String evictedSessionId;
        private final String replacedSessionId;
    }

    @Getter
    @AllArgsConstructor
    public static class ActiveSession {
        private final String sessionId;
        private final String deviceId;
        private final Long ttlSeconds;
        private final LocalDateTime lastActiveTime;
    }

    @Getter
    public static class ConcurrentSessionLimitException extends RuntimeException {
        private final int maxConcurrentSessions;
        private final int activeSessionCount;

        public ConcurrentSessionLimitException(int maxConcurrentSessions, int activeSessionCount) {
            super("当前账号已达到最大并发登录会话数(" + maxConcurrentSessions + ")");
            this.maxConcurrentSessions = maxConcurrentSessions;
            this.activeSessionCount = activeSessionCount;
        }
    }
}
