package org.ocean.admin.auth;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ocean.admin.platform.api.CurrentUserAccessor;
import org.ocean.admin.platform.identity.session.RefreshTokenLifecycleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 面向第一方管理前端的用户名密码登录与会话注销 API。 */
@Tag(name = "用户认证", description = "第一方管理前端登录、平台登录和注销")
@RestController
@RequestMapping("/api/user")
public class UserAuthenticationController {

    private final PasswordLoginTokenService loginTokenService;
    private final CurrentUserAccessor currentUserAccessor;
    private final RefreshTokenLifecycleService lifecycleService;

    public UserAuthenticationController(
            PasswordLoginTokenService loginTokenService,
            CurrentUserAccessor currentUserAccessor,
            RefreshTokenLifecycleService lifecycleService) {
        this.loginTokenService = loginTokenService;
        this.currentUserAccessor = currentUserAccessor;
        this.lifecycleService = lifecycleService;
    }

    @Operation(summary = "用户登录", description = "用户名密码登录并返回 SAS JWT 与轮换刷新令牌")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        applySessionMetadata(request, servletRequest);
        return loginTokenService.login(request, clientIp(servletRequest));
    }

    @Operation(summary = "平台登录", description = "支持显式传入 platformCode 的第一方平台登录入口")
    @PostMapping("/platform-login")
    public LoginResponse platformLogin(
            @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        if (request.platformCode() == null || request.platformCode().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "platformCode 不能为空");
        }
        applySessionMetadata(request, servletRequest);
        return loginTokenService.login(request, clientIp(servletRequest));
    }

    @Operation(summary = "用户登出", description = "撤销当前数据库会话、刷新令牌族及 Redis 投影")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        lifecycleService.revokeSession(
                currentUserAccessor.requiredCurrentUser().sessionId(), "USER_LOGOUT_API");
    }

    private static void applySessionMetadata(LoginRequest request, HttpServletRequest servletRequest) {
        servletRequest.setAttribute("ocean.login.device-id", request.deviceId());
        servletRequest.setAttribute("ocean.login.device-name", request.deviceName());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 200) String password,
            @Size(max = 50) String platformCode,
            @Size(max = 200) String deviceId,
            @Size(max = 200) String deviceName) { }

    public record LoginResponse(
            String token,
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            UUID userId,
            String username,
            String platform,
            UUID sessionId,
            List<String> roles,
            List<String> permissions) { }
}
