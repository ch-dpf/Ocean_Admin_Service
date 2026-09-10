package org.ocean.admin.platform.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.audit.utils.ApiExceptionLogRecorder;
import org.ocean.admin.platform.audit.utils.RequestIpResolver;
import org.ocean.admin.platform.identity.entity.SysUser;
import org.ocean.admin.platform.identity.service.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "登录认证管理", description = "登录认证管理接口")
public class AuthController {

    private final AuthService authService;

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

    /**
     * 获取当前用户信息
     */
    @Operation(summary = "获取用户信息", description = "根据Token获取当前登录用户信息")
    @GetMapping("/info")
    public ResponseResult<SysUser> getUserInfo(@RequestHeader("Authorization") String authorization) {
        try {
            String token = authorization.replace("Bearer ", "");
            SysUser user = authService.getUserInfoByToken(token);
            if (user == null) {
                return ResponseResult.error("用户不存在");
            }
            return ResponseResult.success(user);
        } catch (Exception e) {
            return ResponseResult.error("获取用户信息失败: " + e.getMessage());
        }
    }

    /**
     * 验证Token
     */
    @Operation(summary = "验证Token", description = "验证JWT Token是否有效")
    @PostMapping("/validate-token")
    public ResponseResult<Boolean> validateToken(@RequestBody Map<String, String> request) {
        try {
            String token = request.get("token");
            boolean valid = authService.validateToken(token);
            return ResponseResult.success(valid);
        } catch (Exception e) {
            return ResponseResult.error("验证失败: " + e.getMessage());
        }
    }

    /**
     * 敏感配置编辑前管理员权限确认
     */
    @Operation(summary = "管理员权限确认", description = "校验当前登录账号是否具备管理员权限")
    @PostMapping("/confirm-admin-access")
    public ResponseResult<Boolean> confirmAdminAccess(@RequestHeader("Authorization") String authorization) {
        try {
            String token = authorization.replace("Bearer ", "");
            authService.confirmAdminAccess(token);
            return ResponseResult.success(true);
        } catch (RuntimeException e) {
            return ResponseResult.error(e.getMessage());
        } catch (Exception e) {
            return ResponseResult.error("校验失败: " + e.getMessage());
        }
    }

    @Operation(summary = "用户登出", description = "清理当前登录会话")
    @PostMapping("/logout")
    public ResponseResult<Void> logout(@RequestHeader("Authorization") String authorization) {
        try {
            String token = authorization.replace("Bearer ", "");
            authService.logout(token);
            return ResponseResult.success("登出成功", null);
        } catch (Exception e) {
            return ResponseResult.error("登出失败: " + e.getMessage());
        }
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
            String ipAddress = RequestIpResolver.resolve(request);

            Map<String, Object> result = authService.login(username, password, platform, deviceId, browser, os, userAgent, ipAddress);
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
}
