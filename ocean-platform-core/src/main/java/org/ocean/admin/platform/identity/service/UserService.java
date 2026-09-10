package org.ocean.admin.platform.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class UserService {

    private final SysUserMapper userMapper;

    private final AuthSessionContractService authSessionContractService;

    private final ApplicationEventPublisher eventPublisher;

    private final UserRoleService userRoleService;

    private final UserSessionService userSessionService;

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
                                     String ipAddress) {
        String resolvedUserAgent = resolveUserAgent(userAgent);
        String resolvedIpAddress = resolveIpAddress(ipAddress);
        try {
            return doLogin(username, password, platform, deviceId, browser, os, resolvedUserAgent, resolvedIpAddress);
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
                                        String ipAddress) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new RuntimeException("用户名和密码不能为空");
        }

        // 查询用户
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        SysUser user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }

        securityPolicyService.assertUserNotLocked(user);

        // 验证密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            securityPolicyService.onPasswordFailure(user);
            throw new RuntimeException("用户名或密码错误");
        }

        // 检查用户状态
        if (user.getStatus() != 1) {
            throw new RuntimeException("用户已被禁用");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!isUserWithinValidPeriod(user, now)) {
            throw new RuntimeException("当前账号不在有效期内，无法登录");
        }

        int maxDevices = resolveMaxLoginDevices(user);
        int activeSessions = countActiveSessions(user.getId());
        String requestedPlatformCode = resolvePlatformCode(platform);
        String resolvedDeviceId = resolveDeviceId(deviceId, requestedPlatformCode, userAgent, ipAddress);
        String resolvedUserAgent = userAgent;
        String resolvedBrowser = normalizeOptional(browser);
        String resolvedOs = normalizeOptional(os);
        String resolvedIpAddress = ipAddress;
        boolean currentDeviceActive = userSessionService.hasActiveDeviceSession(user.getId(), resolvedDeviceId);
        if (activeSessions >= maxDevices) {
            if (!currentDeviceActive) {
                throw new RuntimeException("已达到最大同时登录设备数限制(" + maxDevices + ")");
            }
        }

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

        // 生成 Token 并写入设备会话契约
        AuthSessionContractService.LoginSession loginSession = authSessionContractService.createLoginSession(
                user.getUsername(),
                user.getId(),
                resolvedDeviceId
        );
        int activeSessionsAfterLogin = countActiveSessions(user.getId());

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
        result.put("maxLoginDevices", maxDevices);
        result.put("activeSessionCount", activeSessionsAfterLogin);
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

    private int resolveMaxLoginDevices(SysUser user) {
        Integer maxDevices = user.getMaxLoginDevices();
        if (maxDevices == null || maxDevices <= 0) {
            return 1;
        }
        return Math.min(maxDevices, 10);
    }

    private int countActiveSessions(Long userId) {
        return userSessionService.countActiveSessions(userId);
    }

    private String resolveCurrentRequestIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        return request == null ? null : request.getRemoteAddr();
    }

    private String resolveCurrentDeviceId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        if (request == null) {
            return null;
        }
        String userAgent = request.getHeader("User-Agent");
        String remoteAddr = request.getRemoteAddr();
        String source = String.format("%s|%s|OCEAN_CLOUD", remoteAddr == null ? "-" : remoteAddr, userAgent == null ? "-" : userAgent);
        return "fp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveIpAddress(String ipAddress) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            return ipAddress.trim();
        }
        return resolveCurrentRequestIp();
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
        String remoteAddr = ipAddress != null && !ipAddress.isBlank() ? ipAddress : request.getRemoteAddr();
        String source = String.format("%s|%s|%s", remoteAddr == null ? "-" : remoteAddr,
                resolvedUserAgent == null ? "-" : resolvedUserAgent,
                platform == null ? "-" : platform);
        return "fp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
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
        data.put("maxLoginDevices", null);
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

        if (!userSessionService.isSessionOwned(userId, sessionId)) {
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
        data.put("deviceId", userSessionService.findDeviceIdBySession(userId, sessionId));
        data.put("platformCodes", userRoleService.getUserPlatformCodes(userId));
        data.put("maxLoginDevices", resolveMaxLoginDevices(user));
        return data;
    }

    public void logout(String token) {
        AuthSessionContractService.AuthenticatedSession session = authSessionContractService.authenticate(token);
        if (session == null) {
            return;
        }
        authSessionContractService.logout(token);
        try {
            eventPublisher.publishEvent(new UserLogoutEvent(session.getSessionId(), LocalDateTime.now()));
        } catch (Exception e) {
            log.error("发布用户登出事件失败: {}", e.getMessage(), e);
        }
    }

    public boolean isSessionActive(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return userSessionService.isSessionActive(userId, sessionId, authSessionContractService.getExpirationTimeSeconds());
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

}
