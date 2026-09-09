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
    private final UserSessionService userSessionService;

    public LoginSession createLoginSession(String username, Long userId, String deviceId) {
        String sessionId = UUID.randomUUID().toString();
        String token = jwtUtil.generateToken(username, userId, sessionId);
        long expiresInSeconds = jwtUtil.getExpirationTimeSeconds();
        userSessionService.registerSession(userId, deviceId, sessionId, expiresInSeconds);
        return new LoginSession(token, sessionId, expiresInSeconds);
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
        if (!userSessionService.isSessionActive(userId, sessionId, jwtUtil.getExpirationTimeSeconds())) {
            return null;
        }
        return new AuthenticatedSession(userId, sessionId, username);
    }

    public boolean validateToken(String token) {
        return authenticate(token) != null;
    }

    public long getExpirationTimeSeconds() {
        return jwtUtil.getExpirationTimeSeconds();
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
        userSessionService.removeSession(userId, sessionId);
    }

    @Getter
    @AllArgsConstructor
    public static class LoginSession {
        private final String token;
        private final String sessionId;
        private final long expiresInSeconds;
    }

    @Getter
    @AllArgsConstructor
    public static class AuthenticatedSession {
        private final Long userId;
        private final String sessionId;
        private final String username;
    }
}

