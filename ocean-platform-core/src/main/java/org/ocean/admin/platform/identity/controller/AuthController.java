package org.ocean.admin.platform.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.identity.service.UserService;
import org.ocean.admin.platform.audit.utils.ApiExceptionLogRecorder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "登录认证管理", description = "登录认证管理接口")
public class AuthController {

    private final UserService userService;

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

            Map<String, Object> result = userService.login(username, password, platform, deviceId, browser, os, userAgent, ipAddress);
            return ResponseResult.success("登录成功", result);
        } catch (RuntimeException e) {
            return ResponseResult.error(e.getMessage());
        } catch (Exception e) {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                apiExceptionLogRecorder.record(attributes.getRequest(), e, String.valueOf(loginRequest));
            }
            return ResponseResult.error("登录失败: " + e.getMessage());
        }
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
