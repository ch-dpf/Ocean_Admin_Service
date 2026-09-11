package org.ocean.admin.platform.identity.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的用户会话服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthUserSessionService {

    private static final String USER_DEVICE_SET_KEY = "ocean-admin:auth:user:%d:devices";
    private static final String USER_DEVICE_SESSION_KEY = "ocean-admin:auth:user:%d:device:%s:session";
    private static final String USER_DEVICE_LAST_ACTIVE_KEY = "ocean-admin:auth:user:%d:device:%s:last-active";
    private static final String SESSION_OWNER_KEY = "ocean-admin:auth:session:%s:owner";

    private final StringRedisTemplate redisTemplate;

    public int countActiveSessions(Long userId) {
        String userDeviceSetKey = buildUserDeviceSetKey(userId);
        Set<String> encodedDeviceIds = redisTemplate.opsForSet().members(userDeviceSetKey);
        if (encodedDeviceIds == null || encodedDeviceIds.isEmpty()) {
            return 0;
        }

        int active = 0;
        for (String encodedDeviceId : encodedDeviceIds) {
            String sessionKey = buildUserDeviceSessionKey(userId, encodedDeviceId);
            String sessionId = redisTemplate.opsForValue().get(sessionKey);
            if (sessionId != null && !sessionId.isBlank()) {
                active++;
            } else {
                redisTemplate.opsForSet().remove(userDeviceSetKey, encodedDeviceId);
            }
        }
        return active;
    }

    public void registerSession(Long userId, String deviceId, String sessionId, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            ttlSeconds = 1;
        }
        String normalizedDeviceId = normalizeDeviceId(deviceId);
        String encodedDeviceId = encodeDeviceId(normalizedDeviceId);

        String userDeviceSetKey = buildUserDeviceSetKey(userId);
        String deviceSessionKey = buildUserDeviceSessionKey(userId, encodedDeviceId);
        String previousSessionId = redisTemplate.opsForValue().get(deviceSessionKey);
        if (previousSessionId != null && !previousSessionId.isBlank() && !previousSessionId.equals(sessionId)) {
            redisTemplate.delete(buildSessionOwnerKey(previousSessionId));
        }

        String ownerKey = buildSessionOwnerKey(sessionId);
        String lastActiveKey = buildUserDeviceLastActiveKey(userId, encodedDeviceId);
        redisTemplate.opsForValue().set(deviceSessionKey, sessionId, ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(lastActiveKey, String.valueOf(System.currentTimeMillis()), ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(ownerKey, String.valueOf(userId), ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForSet().add(userDeviceSetKey, encodedDeviceId);

        Long currentTtl = redisTemplate.getExpire(userDeviceSetKey, TimeUnit.SECONDS);
        if (currentTtl < ttlSeconds) {
            redisTemplate.expire(userDeviceSetKey, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    public boolean hasActiveDeviceSession(Long userId, String deviceId) {
        String normalizedDeviceId = normalizeDeviceId(deviceId);
        String encodedDeviceId = encodeDeviceId(normalizedDeviceId);
        String deviceSessionKey = buildUserDeviceSessionKey(userId, encodedDeviceId);
        String sessionId = redisTemplate.opsForValue().get(deviceSessionKey);
        if (sessionId == null || sessionId.isBlank()) {
            redisTemplate.opsForSet().remove(buildUserDeviceSetKey(userId), encodedDeviceId);
            return false;
        }
        String ownerUserId = redisTemplate.opsForValue().get(buildSessionOwnerKey(sessionId));
        if (!String.valueOf(userId).equals(ownerUserId)) {
            redisTemplate.delete(deviceSessionKey);
            redisTemplate.opsForSet().remove(buildUserDeviceSetKey(userId), encodedDeviceId);
            return false;
        }
        return true;
    }

    public boolean isSessionActive(Long userId, String sessionId, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            ttlSeconds = 1;
        }
        String ownerKey = buildSessionOwnerKey(sessionId);
        String ownerUserId = redisTemplate.opsForValue().get(ownerKey);
        boolean active = String.valueOf(userId).equals(ownerUserId);
        if (!active) {
            return false;
        }

        String encodedDeviceId = findDeviceBySession(userId, sessionId);
        if (encodedDeviceId == null) {
            redisTemplate.delete(ownerKey);
            return false;
        }

        String userDeviceSetKey = buildUserDeviceSetKey(userId);
        String deviceSessionKey = buildUserDeviceSessionKey(userId, encodedDeviceId);
        String lastActiveKey = buildUserDeviceLastActiveKey(userId, encodedDeviceId);

        redisTemplate.expire(ownerKey, ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.expire(deviceSessionKey, ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(lastActiveKey, String.valueOf(System.currentTimeMillis()), ttlSeconds, TimeUnit.SECONDS);
        Long currentTtl = redisTemplate.getExpire(userDeviceSetKey, TimeUnit.SECONDS);
        if (currentTtl < ttlSeconds) {
            redisTemplate.expire(userDeviceSetKey, ttlSeconds, TimeUnit.SECONDS);
        }
        return true;
    }

    public boolean isSessionOwned(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        String ownerUserId = redisTemplate.opsForValue().get(buildSessionOwnerKey(sessionId));
        if (!String.valueOf(userId).equals(ownerUserId)) {
            return false;
        }
        return findDeviceBySession(userId, sessionId) != null;
    }

    public String findDeviceIdBySession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String ownerUserId = redisTemplate.opsForValue().get(buildSessionOwnerKey(sessionId));
        if (!String.valueOf(userId).equals(ownerUserId)) {
            return null;
        }
        String encodedDeviceId = findDeviceBySession(userId, sessionId);
        if (encodedDeviceId == null) {
            return null;
        }
        return decodeDeviceId(encodedDeviceId);
    }

    public void removeSession(Long userId, String sessionId) {
        String ownerKey = buildSessionOwnerKey(sessionId);
        String ownerUserId = redisTemplate.opsForValue().get(ownerKey);
        if (!String.valueOf(userId).equals(ownerUserId)) {
            redisTemplate.delete(ownerKey);
            return;
        }

        String encodedDeviceId = findDeviceBySession(userId, sessionId);
        redisTemplate.delete(ownerKey);
        if (encodedDeviceId != null) {
            redisTemplate.delete(buildUserDeviceSessionKey(userId, encodedDeviceId));
            redisTemplate.delete(buildUserDeviceLastActiveKey(userId, encodedDeviceId));
            redisTemplate.opsForSet().remove(buildUserDeviceSetKey(userId), encodedDeviceId);
        }
    }

    public List<ActiveDeviceSession> listActiveDevices(Long userId) {
        Set<String> encodedDeviceIds = redisTemplate.opsForSet().members(buildUserDeviceSetKey(userId));
        if (encodedDeviceIds == null || encodedDeviceIds.isEmpty()) {
            return List.of();
        }

        List<ActiveDeviceSession> result = new ArrayList<>();
        for (String encodedDeviceId : encodedDeviceIds) {
            String sessionKey = buildUserDeviceSessionKey(userId, encodedDeviceId);
            String sessionId = redisTemplate.opsForValue().get(sessionKey);
            if (sessionId == null || sessionId.isBlank()) {
                redisTemplate.delete(buildUserDeviceLastActiveKey(userId, encodedDeviceId));
                redisTemplate.opsForSet().remove(buildUserDeviceSetKey(userId), encodedDeviceId);
                continue;
            }

            String ownerUserId = redisTemplate.opsForValue().get(buildSessionOwnerKey(sessionId));
            if (!String.valueOf(userId).equals(ownerUserId)) {
                redisTemplate.delete(sessionKey);
                redisTemplate.delete(buildUserDeviceLastActiveKey(userId, encodedDeviceId));
                redisTemplate.opsForSet().remove(buildUserDeviceSetKey(userId), encodedDeviceId);
                continue;
            }

            result.add(new ActiveDeviceSession(
                    decodeDeviceId(encodedDeviceId),
                    sessionId,
                    redisTemplate.getExpire(sessionKey, TimeUnit.SECONDS),
                    readLastActiveTime(userId, encodedDeviceId)
            ));
        }
        return result;
    }

    private String buildUserDeviceSetKey(Long userId) {
        return String.format(USER_DEVICE_SET_KEY, userId);
    }

    private String buildUserDeviceSessionKey(Long userId, String encodedDeviceId) {
        return String.format(USER_DEVICE_SESSION_KEY, userId, encodedDeviceId);
    }

    private String buildUserDeviceLastActiveKey(Long userId, String encodedDeviceId) {
        return String.format(USER_DEVICE_LAST_ACTIVE_KEY, userId, encodedDeviceId);
    }

    private String buildSessionOwnerKey(String sessionId) {
        return String.format(SESSION_OWNER_KEY, sessionId);
    }

    private String normalizeDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return "unknown-device";
        }
        return deviceId.trim();
    }

    private String encodeDeviceId(String deviceId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(deviceId.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeDeviceId(String encodedDeviceId) {
        try {
            return new String(Base64.getUrlDecoder().decode(encodedDeviceId), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            log.warn("解码设备ID失败: {}", encodedDeviceId);
            return encodedDeviceId;
        }
    }

    private LocalDateTime readLastActiveTime(Long userId, String encodedDeviceId) {
        String raw = redisTemplate.opsForValue().get(buildUserDeviceLastActiveKey(userId, encodedDeviceId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long epochMilli = Long.parseLong(raw);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
        } catch (NumberFormatException ex) {
            log.warn("解析最后活跃时间失败: {}", raw);
            return null;
        }
    }

    private String findDeviceBySession(Long userId, String sessionId) {
        Set<String> encodedDeviceIds = redisTemplate.opsForSet().members(buildUserDeviceSetKey(userId));
        if (encodedDeviceIds == null || encodedDeviceIds.isEmpty()) {
            return null;
        }
        for (String encodedDeviceId : encodedDeviceIds) {
            String deviceSessionKey = buildUserDeviceSessionKey(userId, encodedDeviceId);
            String existingSessionId = redisTemplate.opsForValue().get(deviceSessionKey);
            if (sessionId.equals(existingSessionId)) {
                return encodedDeviceId;
            }
            if (existingSessionId == null || existingSessionId.isBlank()) {
                redisTemplate.opsForSet().remove(buildUserDeviceSetKey(userId), encodedDeviceId);
            }
        }
        return null;
    }

    @Getter
    @AllArgsConstructor
    public static class ActiveDeviceSession {
        private final String deviceId;
        private final String sessionId;
        private final Long ttlSeconds;
        private final LocalDateTime lastActiveTime;
    }
}
