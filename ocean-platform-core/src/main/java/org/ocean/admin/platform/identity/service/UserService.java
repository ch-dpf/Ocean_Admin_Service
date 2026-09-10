package org.ocean.admin.platform.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.platform.audit.entity.SysLoginLog;
import org.ocean.admin.platform.audit.service.SysLoginLogService;
import org.ocean.admin.platform.identity.entity.SysUser;
import org.ocean.admin.platform.identity.mapper.SysUserMapper;
import org.ocean.admin.platform.identity.utils.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

    private final SysLoginLogService loginLogService;

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
        String resolvedUserAgent = resolveUserAgent(userAgent);
        String resolvedBrowser = resolveBrowser(browser, resolvedUserAgent);
        String resolvedOs = resolveOs(os, resolvedUserAgent);
        String resolvedIpAddress = resolveIpAddress(ipAddress);
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

        // 记录登录日志
        recordLoginLog(user, true, "登录成功", loginSession.getSessionId(), requestedPlatformCode, resolvedDeviceId, resolvedBrowser, resolvedOs, resolvedUserAgent, resolvedIpAddress);
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

        log.info("用户登录成功: {}", username);
        return result;
    }

    /**
     * 记录登录日志
     *
     * @param user 用户信息
     * @param success 是否成功
     * @param message 提示信息
     */
    private void recordLoginLog(SysUser user,
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
            SysLoginLog loginLog = new SysLoginLog();
            loginLog.setUserId(user.getId());
            loginLog.setUsername(user.getUsername());
            loginLog.setLoginType("PASSWORD");
            loginLog.setStatus(success ? 1 : 0);
            loginLog.setMessage(message);
            loginLog.setLoginTime(LocalDateTime.now());
            loginLog.setSessionId(sessionId);
            loginLog.setPlatform(platform);
            loginLog.setDeviceId(deviceId);
            loginLog.setIpAddress(ipAddress);
            loginLog.setUserAgent(userAgent);
            loginLog.setBrowser(browser);
            loginLog.setOs(os);

            // 获取请求信息
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                if (loginLog.getIpAddress() == null || loginLog.getIpAddress().isBlank()) {
                    loginLog.setIpAddress(request.getRemoteAddr());
                }
                if (loginLog.getUserAgent() == null || loginLog.getUserAgent().isBlank()) {
                    loginLog.setUserAgent(request.getHeader("User-Agent"));
                }

                // 解析浏览器和操作系统信息（简单实现）
                String requestUserAgent = request.getHeader("User-Agent");
                if ((loginLog.getBrowser() == null || loginLog.getBrowser().isBlank()) && requestUserAgent != null) {
                    loginLog.setBrowser(parseBrowser(requestUserAgent));
                }
                if ((loginLog.getOs() == null || loginLog.getOs().isBlank()) && requestUserAgent != null) {
                    loginLog.setOs(parseOS(requestUserAgent));
                }
            }

            loginLogService.recordLoginLog(loginLog);
        } catch (Exception e) {
            log.error("记录登录日志失败: {}", e.getMessage());
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

    private String resolveBrowser(String browser, String userAgent) {
        if (browser != null && !browser.isBlank()) {
            return browser.trim();
        }
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return parseBrowser(userAgent);
    }

    private String resolveOs(String os, String userAgent) {
        if (os != null && !os.isBlank()) {
            return os.trim();
        }
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return parseOS(userAgent);
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
     * 解析浏览器信息
     */
    private String parseBrowser(String userAgent) {
        if (userAgent.contains("Chrome")) return "Chrome";
        if (userAgent.contains("Firefox")) return "Firefox";
        if (userAgent.contains("Safari")) return "Safari";
        if (userAgent.contains("Edge")) return "Edge";
        if (userAgent.contains("MSIE") || userAgent.contains("Trident")) return "IE";
        return "Unknown";
    }

    /**
     * 解析操作系统信息
     */
    private String parseOS(String userAgent) {
        if (userAgent.contains("Windows NT 10.0")) return "Windows 10";
        if (userAgent.contains("Windows NT 6.3")) return "Windows 8.1";
        if (userAgent.contains("Windows NT 6.1")) return "Windows 7";
        if (userAgent.contains("Mac OS X")) return "Mac OS";
        if (userAgent.contains("Linux")) return "Linux";
        if (userAgent.contains("Android")) return "Android";
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) return "iOS";
        return "Unknown";
    }



}
