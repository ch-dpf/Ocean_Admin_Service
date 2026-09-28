package org.ocean.admin.platform.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.platform.audit.utils.RequestIpResolver;
import org.ocean.admin.platform.identity.entity.SysUser;
import org.ocean.admin.platform.identity.event.UserLoginEvent;
import org.ocean.admin.platform.identity.event.UserLogoutEvent;
import org.ocean.admin.platform.identity.mapper.SysUserMapper;
import org.ocean.admin.platform.identity.utils.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户服务
 *
 * @author DeepOcean
 * @since 2026-09-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;

    private final AuthSessionContractService authSessionContractService;

    private final ApplicationEventPublisher eventPublisher;

    private final UserRoleService userRoleService;

    private final AuthUserSessionService authUserSessionService;

    private final SecurityPolicyService securityPolicyService;

    private final JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();


    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录结果（包含token和用户信息）
     */
    public Map<String, Object> login(String username,
                                     String password,
                                     String platform,
                                     String deviceId,
                                     String browser,
                                     String os,
                                     String userAgent,
                                     String ipAddress,
                                     boolean forceLogin,
                                     String currentToken) {
        String resolvedUserAgent = resolveUserAgent(userAgent);
        String resolvedIpAddress = resolveIpAddress(ipAddress);
        try {
            return doLogin(username, password, platform, deviceId, browser, os, resolvedUserAgent, resolvedIpAddress,
                    forceLogin, currentToken);
        } catch (RuntimeException e) {
            publishLoginEvent(null, username, false, e.getMessage(), null, platform, deviceId,
                    browser, os, resolvedUserAgent, resolvedIpAddress);
            throw e;
        }
    }

    private Map<String, Object> doLogin(String username,
                                        String password,
                                        String platform,
                                        String deviceId,
                                        String browser,
                                        String os,
                                        String userAgent,
                                         String ipAddress,
                                         boolean forceLogin,
                                         String currentToken) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new RuntimeException("用户名和密码不能为空");
        }

        // 查询用户
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        SysUser user = userMapper.selectOne(wrapper);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 确认用户未被锁定
        securityPolicyService.assertUserNotLocked(user);

        // 验证密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            // 密码失败时
            securityPolicyService.onPasswordFailure(user);
            throw new RuntimeException("用户名或密码错误");
        }

        // 检查用户状态
        if (user.getStatus() != 1) {
            throw new RuntimeException("用户已被禁用");
        }

        LocalDateTime now = LocalDateTime.now();
        // 用户是否不在有效期内
        if (!isUserWithinValidPeriod(user, now)) {
            throw new RuntimeException("当前账号不在有效期内，无法登录");
        }

        int maxConcurrentSessions = resolveMaxConcurrentSessions(user);

        String requestedPlatformCode = resolvePlatformCode(platform);
        String resolvedDeviceId = resolveDeviceId(deviceId, requestedPlatformCode, userAgent, ipAddress);
        String resolvedUserAgent = userAgent;
        String resolvedBrowser = normalizeOptional(browser);
        String resolvedOs = normalizeOptional(os);
        String resolvedIpAddress = ipAddress;
        List<String> roleCodes = userRoleService.getUserRoleCodes(user.getId());
        String roleType = userRoleService.resolveRoleType(roleCodes);
        String normalizedPlatform = userRoleService.normalizePlatform(platform);
        if (!userRoleService.canLoginPlatform(roleType, normalizedPlatform)) {
            if (UserRoleService.ROLE_TYPE_NORMAL_USER.equals(roleType)) {
                throw new RuntimeException("普通用户不可登录数据管理平台，请使用业务平台");
            }
            throw new RuntimeException("当前角色仅允许登录数据管理平台");
        }
        if (!userRoleService.canUserLoginPlatform(user.getId(), normalizedPlatform)) {
            throw new RuntimeException("当前用户未授权登录该平台");
        }

        String replaceSessionId = authSessionContractService.resolveOwnedSessionId(currentToken, user.getId());
        // 生成 Token 并原子注册并发登录会话
        AuthSessionContractService.LoginSession loginSession = authSessionContractService.createLoginSession(
                user.getUsername(),
                user.getId(),
                resolvedDeviceId,
                maxConcurrentSessions,
                forceLogin,
                replaceSessionId
        );
        publishLogoutEvent(loginSession.getReplacedSessionId());
        publishLogoutEvent(loginSession.getEvictedSessionId());

        // 更新最后登录时间和IP
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(resolvedIpAddress);
        userMapper.updateById(user);

        securityPolicyService.onLoginSuccess(user.getId());

        // 返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("token", loginSession.getToken());
        result.put("username", user.getUsername());
        result.put("userId", user.getId());
        result.put("role", roleCodes.isEmpty() ? "USER" : String.join(",", roleCodes));
        result.put("roles", roleCodes);
        result.put("roleType", roleType);
        result.put("platform", requestedPlatformCode);
        result.put("platformCodes", userRoleService.getUserPlatformCodes(user.getId()));
        result.put("deviceId", resolvedDeviceId);
        result.put("sessionId", loginSession.getSessionId());
        result.put("expiresInSeconds", loginSession.getExpiresInSeconds());
        result.put("idleTimeoutSeconds", loginSession.getIdleTimeoutSeconds());
        result.put("maxConcurrentSessions", maxConcurrentSessions);
        result.put("activeSessionCount", loginSession.getActiveSessionCount());
        result.put("forceLogin", forceLogin);
        result.put("evictedSessionId", loginSession.getEvictedSessionId());
        result.put("authProvider", "OCEAN_CLOUD");

        publishLoginEvent(user.getId(), user.getUsername(), true, "登录成功", loginSession.getSessionId(),
                requestedPlatformCode, resolvedDeviceId, resolvedBrowser, resolvedOs, resolvedUserAgent, resolvedIpAddress);
        log.info("用户登录成功: {}", username);
        return result;
    }

    private void publishLoginEvent(Long userId,
                                   String username,
                                   boolean success,
                                   String message,
                                   String sessionId,
                                   String platform,
                                   String deviceId,
                                   String browser,
                                   String os,
                                   String userAgent,
                                   String ipAddress) {
        try {
            eventPublisher.publishEvent(new UserLoginEvent(userId, username, success, message, sessionId,
                    platform, deviceId, browser, os, userAgent, ipAddress, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("发布用户登录事件失败: {}", e.getMessage(), e);
        }
    }

    private boolean isUserWithinValidPeriod(SysUser user, LocalDateTime now) {
        Integer permanent = user.getIsPermanentValid();
        if (permanent != null && permanent == 1) {
            return true;
        }
        LocalDateTime validFrom = user.getValidFrom();
        LocalDateTime validTo = user.getValidTo();
        if (validFrom != null && now.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !now.isAfter(validTo);
    }

    private int resolveMaxConcurrentSessions(SysUser user) {
        Integer maxSessions = user.getMaxConcurrentSessions();
        if (maxSessions == null || maxSessions <= 0) {
            return 1;
        }
        return Math.min(maxSessions, 10);
    }

    private String resolveIpAddress(String ipAddress) {
        return RequestIpResolver.resolveOrFallback(ipAddress, currentRequest());
    }

    private String resolveUserAgent(String userAgent) {
        if (userAgent != null && !userAgent.isBlank()) {
            return userAgent.trim();
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null || attributes.getRequest() == null) {
            return null;
        }
        return attributes.getRequest().getHeader("User-Agent");
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String resolvePlatformCode(String platform) {
        if (platform == null || platform.isBlank()) {
            return "OCEAN_VISION";
        }
        return platform.trim().toUpperCase();
    }

    private String resolveDeviceId(String deviceId, String platform, String userAgent, String ipAddress) {
        if (deviceId != null && !deviceId.isBlank()) {
            return deviceId.trim();
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "anonymous-device";
        }
        HttpServletRequest request = attributes.getRequest();
        if (request == null) {
            return "anonymous-device";
        }
        String resolvedUserAgent = userAgent != null && !userAgent.isBlank() ? userAgent : request.getHeader("User-Agent");
        String remoteAddr = RequestIpResolver.resolveOrFallback(ipAddress, request);
        String source = String.format("%s|%s|%s", remoteAddr == null ? "-" : remoteAddr,
                resolvedUserAgent == null ? "-" : resolvedUserAgent,
                platform == null ? "-" : platform);
        return "fp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }


    /**
     * 验证Token是否有效
     *
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        return authSessionContractService.validateToken(token);
    }

    public Map<String, Object> introspectToken(String token) {
        Map<String, Object> data = new HashMap<>();
        data.put("active", false);
        data.put("userId", null);
        data.put("sessionId", null);
        data.put("deviceId", null);
        data.put("platformCodes", List.of());
        data.put("exp", null);
        data.put("maxConcurrentSessions", null);
        data.put("reason", "missing_token");

        String status = jwtUtil.classifyTokenStatus(token);
        if (!"ok".equals(status)) {
            data.put("reason", status);
            return data;
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        String sessionId = jwtUtil.getSessionIdFromToken(token);
        Long exp = jwtUtil.getExpirationEpochSeconds(token);
        data.put("userId", userId);
        data.put("sessionId", sessionId);
        data.put("exp", exp);

        if (userId == null || sessionId == null || sessionId.isBlank()) {
            data.put("reason", "malformed");
            return data;
        }

        if (!authUserSessionService.isSessionOwned(userId, sessionId)) {
            data.put("reason", "session_revoked");
            return data;
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null || (user.getDeleted() != null && user.getDeleted() == 1)) {
            data.put("reason", "user_not_found");
            return data;
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            data.put("reason", "user_disabled");
            return data;
        }

        data.put("active", true);
        data.put("reason", "ok");
        data.put("deviceId", authUserSessionService.findDeviceIdBySession(userId, sessionId));
        data.put("platformCodes", userRoleService.getUserPlatformCodes(userId));
        data.put("maxConcurrentSessions", resolveMaxConcurrentSessions(user));
        return data;
    }

    public void logout(String token) {
        AuthSessionContractService.AuthenticatedSession session = authSessionContractService.authenticate(token);
        if (session == null) {
            return;
        }
        authSessionContractService.logout(token);
        publishLogoutEvent(session.getSessionId());
    }

    private void publishLogoutEvent(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            eventPublisher.publishEvent(new UserLogoutEvent(sessionId, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("发布用户登出事件失败: {}", e.getMessage(), e);
        }
    }

    public void confirmAdminAccess(String token) {
        if (!authSessionContractService.validateToken(token)) {
            throw new RuntimeException("登录状态无效，请重新登录");
        }

        Long userId = authSessionContractService.getUserIdFromToken(token);
        if (userId == null) {
            throw new RuntimeException("无法识别当前用户");
        }

        SysUser currentUser = userMapper.selectById(userId);
        if (currentUser == null || currentUser.getDeleted() != null && currentUser.getDeleted() == 1) {
            throw new RuntimeException("当前用户不存在或已删除");
        }
        if (currentUser.getStatus() == null || currentUser.getStatus() != 1) {
            throw new RuntimeException("当前用户已禁用");
        }

        List<String> roleCodes = userRoleService.getUserRoleCodes(currentUser.getId());
        String roleType = userRoleService.resolveRoleType(roleCodes);
        if (!UserRoleService.ROLE_TYPE_ADMIN.equals(roleType)) {
            throw new RuntimeException("当前账号无管理员权限");
        }
    }


    /**
     * 根据Token获取用户信息
     *
     * @param token JWT Token
     * @return 用户信息
     */
    public SysUser getUserInfoByToken(String token) {
        Long userId = authSessionContractService.getUserIdFromToken(token);
        if (userId == null) {
            return null;
        }
        return userMapper.selectById(userId);
    }



}
