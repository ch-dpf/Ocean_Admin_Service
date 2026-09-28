package org.ocean.admin.platform.identity.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.platform.identity.utils.JwtUtil;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 认证会话契约服务，统一管理 Token 与登录设备会话映射。
 */
@Service
@RequiredArgsConstructor
public class AuthSessionContractService {

    private final JwtUtil jwtUtil;
    private final AuthUserSessionService authUserSessionService;

    public LoginSession createLoginSession(String username,
                                           Long userId,
                                           String deviceId,
                                           int maxConcurrentSessions,
                                           boolean forceLogin,
                                           String replaceSessionId) {
        String sessionId = UUID.randomUUID().toString();
        String token = jwtUtil.generateToken(username, userId, sessionId);
        long expiresInSeconds = jwtUtil.getExpirationTimeSeconds();
        Long absoluteExpireAtSeconds = jwtUtil.getExpirationEpochSeconds(token);
        if (absoluteExpireAtSeconds == null) {
            throw new IllegalStateException("无法获取JWT绝对过期时间");
        }
        AuthUserSessionService.RegistrationResult registration = authUserSessionService.registerSession(
                userId,
                deviceId,
                sessionId,
                absoluteExpireAtSeconds * 1000L,
                maxConcurrentSessions,
                forceLogin,
                replaceSessionId
        );
        return new LoginSession(
                token,
                sessionId,
                expiresInSeconds,
                authUserSessionService.getIdleTimeoutSeconds(),
                registration.getActiveSessionCount(),
                registration.getEvictedSessionId(),
                registration.getReplacedSessionId()
        );
    }

    public AuthenticatedSession authenticate(String token) {
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            return null;
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        String sessionId = jwtUtil.getSessionIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Long absoluteExpireAtSeconds = jwtUtil.getExpirationEpochSeconds(token);
        if (absoluteExpireAtSeconds == null
                || !authUserSessionService.isSessionActive(userId, sessionId, absoluteExpireAtSeconds * 1000L)) {
            return null;
        }
        return new AuthenticatedSession(userId, sessionId, username);
    }

    public boolean validateToken(String token) {
        return authenticate(token) != null;
    }

    public String resolveOwnedSessionId(String token, Long expectedUserId) {
        if (token == null || token.isBlank() || expectedUserId == null || !jwtUtil.validateToken(token)) {
            return null;
        }
        Long tokenUserId = jwtUtil.getUserIdFromToken(token);
        String sessionId = jwtUtil.getSessionIdFromToken(token);
        if (!expectedUserId.equals(tokenUserId) || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return authUserSessionService.isSessionOwned(expectedUserId, sessionId) ? sessionId : null;
    }

    public Long getUserIdFromToken(String token) {
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            return null;
        }
        return jwtUtil.getUserIdFromToken(token);
    }

    public void logout(String token) {
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            return;
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        String sessionId = jwtUtil.getSessionIdFromToken(token);
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        authUserSessionService.removeSession(userId, sessionId);
    }

    @Getter
    @AllArgsConstructor
    public static class LoginSession {
        private final String token;
        private final String sessionId;
        private final long expiresInSeconds;
        private final long idleTimeoutSeconds;
        private final int activeSessionCount;
        private final String evictedSessionId;
        private final String replacedSessionId;
    }

    @Getter
    @AllArgsConstructor
    public static class AuthenticatedSession {
        private final Long userId;
        private final String sessionId;
        private final String username;
    }
}

