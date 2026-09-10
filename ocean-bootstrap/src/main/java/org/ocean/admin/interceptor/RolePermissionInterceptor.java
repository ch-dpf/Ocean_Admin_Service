package org.ocean.admin.interceptor;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.identity.service.AuthSessionContractService;
import org.ocean.admin.platform.identity.service.UserRoleService;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 基于角色的平台与接口权限拦截器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RolePermissionInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/platform-login",
            "/api/auth/validate-token",
            "/api/auth/introspect",
            "/api/xtf/ping-image-batch-by-path",
            "/api/nc/list"
    );

    private final AuthSessionContractService authSessionContractService;
    private final UserRoleService userRoleService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        if (PUBLIC_PATHS.contains(path) || isPublicNcFileInfoPath(request) || isKnowbaseBypassPath(path)) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeError(response, 401, "未授权，请先登录");
            return false;
        }

        String token = authHeader.substring(7);
        AuthSessionContractService.AuthenticatedSession session = authSessionContractService.authenticate(token);
        if (session == null) {
            writeError(response, 401, "登录会话已失效，请重新登录");
            return false;
        }
        Long userId = session.getUserId();

        List<String> roleCodes = userRoleService.getUserRoleCodes(userId);
        String roleType = userRoleService.resolveRoleType(roleCodes);
        String method = request.getMethod();
        boolean analystAdminApi = path.startsWith("/api/analyst-admin/");
        boolean assistantApi = path.startsWith("/api/assistant/");

        if (UserRoleService.ROLE_TYPE_NORMAL_USER.equals(roleType) && !assistantApi) {
            writeError(response, 403, "普通用户不可访问数据管理平台");
            return false;
        }

        if (!analystAdminApi && isAdminManagementPath(path) && !userRoleService.canAccessAdminManagement(roleType)) {
            writeError(response, 403, "当前角色无权限访问成员/日志管理接口");
            return false;
        }

        if (!analystAdminApi && !assistantApi && UserRoleService.ROLE_TYPE_REVIEWER.equals(roleType) && !isReadMethod(method)) {
            writeError(response, 403, "审查员仅可查看数据，不可执行写操作");
            return false;
        }

        return true;
    }

    /**
     * 免鉴权：获取 NC 文件信息 GET /api/nc/{id}
     * 仅放行数字 ID 的 GET，避免误放开 DELETE /api/nc/{id} 等写接口。
     */
    private boolean isPublicNcFileInfoPath(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return path != null && path.matches("^/api/nc/\\d+$");
    }

    private boolean isKnowbaseBypassPath(String path) {
        return path.startsWith("/api/knowbase/")
                || path.startsWith("/api/v1/libraries")
                || path.startsWith("/api/v1/storage")
                || path.startsWith("/api/v1/query-runs")
                || path.startsWith("/api/v1/agents")
                || path.startsWith("/api/v1/ingestion-runs")
                || path.startsWith("/api/v1/observability")
                || path.startsWith("/api/v1/presets");
    }

    private boolean isAdminManagementPath(String path) {
        return path.startsWith("/api/member/")
                || path.startsWith("/api/log/");
    }

    private boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ResponseResult.error(code, message)));
    }
}

