package org.ocean.admin.platform.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.audit.entity.SysLoginLog;
import org.ocean.admin.platform.audit.service.SysLoginLogService;
import org.ocean.admin.platform.identity.service.UserService;
import org.ocean.admin.platform.audit.utils.ApiExceptionLogRecorder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private final SysLoginLogService loginLogService;

    private final ApiExceptionLogRecorder apiExceptionLogRecorder;


    /**
     * 用户登录
     */
    @Operation(summary = "用户登录", description = "用户名密码登录，返回JWT Token")
    @PostMapping("/login")
    public ResponseResult<Map<String, Object>> login(
            @Parameter(description = "登录请求", required = true) @RequestBody Map<String, Object> loginRequest,
            HttpServletRequest request) {
        return doLogin(loginRequest, request);
    }

    private ResponseResult<Map<String, Object>> doLogin(Map<String, Object> loginRequest,
                                                HttpServletRequest request) {
        try {
            String username = toText(loginRequest.get("username"));
            String password = toText(loginRequest.get("password"));
            String platform = firstNonBlank(
                    toText(loginRequest.get("platformCode")),
                    toText(loginRequest.get("platform"))
            );
            String deviceId = firstNonBlank(
                    toText(loginRequest.get("deviceId")),
                    toText(loginRequest.get("device_id"))
            );
            String browser = toText(loginRequest.get("browser"));
            String os = firstNonBlank(
                    toText(loginRequest.get("os")),
                    toText(loginRequest.get("operatingSystem"))
            );
            String userAgent = firstNonBlank(
                    toText(loginRequest.get("userAgent")),
                    toText(loginRequest.get("user_agent")),
                    request == null ? null : request.getHeader("User-Agent")
            );
            String ipAddress = resolveRequestIp(request);

            if (username == null || password == null) {
                // 记录失败日志
                recordFailedLogin(username != null ? username : "unknown", "用户名和密码不能为空");
                return ResponseResult.error("用户名和密码不能为空");
            }

            Map<String, Object> result = userService.login(username, password, platform, deviceId, browser, os, userAgent, ipAddress);
            return ResponseResult.success("登录成功", result);
        } catch (RuntimeException e) {
            // 记录失败日志
            recordFailedLogin(toText(loginRequest.get("username")), e.getMessage());
            return ResponseResult.error(e.getMessage());
        } catch (Exception e) {
            // 记录失败日志
            recordFailedLogin(toText(loginRequest.get("username")), "登录失败: " + e.getMessage());
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                apiExceptionLogRecorder.record(attributes.getRequest(), e, String.valueOf(loginRequest));
            }
            return ResponseResult.error("登录失败: " + e.getMessage());
        }
    }

    /**
     * 记录失败的登录日志
     */
    private void recordFailedLogin(String username, String message) {
        try {
            SysLoginLog loginLog = new SysLoginLog();
            loginLog.setUsername(username);
            loginLog.setLoginType("PASSWORD");
            loginLog.setStatus(0);
            loginLog.setMessage(message);
            loginLog.setLoginTime(LocalDateTime.now());

            // 获取请求信息
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                loginLog.setIpAddress(request.getRemoteAddr());
                loginLog.setUserAgent(request.getHeader("User-Agent"));

                // 解析浏览器和操作系统信息
                String userAgent = request.getHeader("User-Agent");
                if (userAgent != null) {
                    loginLog.setBrowser(parseBrowser(userAgent));
                    loginLog.setOs(parseOS(userAgent));
                }
            }

            loginLogService.recordLoginLog(loginLog);
        } catch (Exception e) {
            log.error("记录登录失败日志失败: {}", e.getMessage());
        }
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

    private String toText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String resolveRequestIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
