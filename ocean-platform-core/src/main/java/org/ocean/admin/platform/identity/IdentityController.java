package org.ocean.admin.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.ocean.admin.kernel.security.CurrentUser;
import org.ocean.admin.platform.api.CurrentUserAccessor;
import org.ocean.admin.platform.identity.IdentityManagementService.PageResult;
import org.ocean.admin.platform.identity.IdentityManagementService.PermissionView;
import org.ocean.admin.platform.identity.IdentityManagementService.RoleAssignmentView;
import org.ocean.admin.platform.identity.IdentityManagementService.RoleView;
import org.ocean.admin.platform.identity.IdentityManagementService.SessionView;
import org.ocean.admin.platform.identity.IdentityManagementService.UserView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 首批 IAM 人工验证接口：当前用户、用户查询、授权关系和会话注销。 */
@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    private static final String USER_READ =
            "hasRole('SUPER_ADMIN') or hasAuthority('admin:user:read')";
    private static final String USER_WRITE =
            "hasRole('SUPER_ADMIN') or hasAuthority('admin:user:write')";

    private final CurrentUserAccessor currentUserAccessor;
    private final IdentityManagementService managementService;

    public IdentityController(
            CurrentUserAccessor currentUserAccessor,
            IdentityManagementService managementService) {
        this.currentUserAccessor = currentUserAccessor;
        this.managementService = managementService;
    }

    @GetMapping("/me")
    public CurrentUser currentUser() {
        return currentUserAccessor.requiredCurrentUser();
    }

    @GetMapping("/users")
    @PreAuthorize(USER_READ)
    public PageResult<UserView> users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return managementService.users(page, size, keyword, status);
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize(USER_READ)
    public UserView user(@PathVariable UUID userId) {
        return managementService.user(userId);
    }

    @GetMapping("/roles")
    @PreAuthorize(USER_READ)
    public List<RoleView> roles() {
        return managementService.roles();
    }

    @GetMapping("/permissions")
    @PreAuthorize(USER_READ)
    public List<PermissionView> permissions() {
        return managementService.permissions();
    }

    @GetMapping("/users/{userId}/roles")
    @PreAuthorize(USER_READ)
    public List<RoleAssignmentView> userRoles(@PathVariable UUID userId) {
        return managementService.userRoles(userId);
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    @PreAuthorize(USER_WRITE)
    public RoleAssignmentView assignRole(
            @PathVariable UUID userId,
            @PathVariable UUID roleId,
            @RequestBody(required = false) RoleAssignmentRequest request) {
        RoleAssignmentRequest command = request == null
                ? new RoleAssignmentRequest(null, null) : request;
        return managementService.assignRole(
                userId, roleId, command.validFrom(), command.validUntil());
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(USER_WRITE)
    public void revokeRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        managementService.revokeRole(
                currentUserAccessor.requiredCurrentUser().userId(), userId, roleId);
    }

    @GetMapping("/me/sessions")
    public List<SessionView> sessions() {
        return managementService.activeSessions(
                currentUserAccessor.requiredCurrentUser().userId());
    }

    @DeleteMapping("/me/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable UUID sessionId) {
        managementService.revokeSession(
                currentUserAccessor.requiredCurrentUser().userId(), sessionId);
    }

    @DeleteMapping("/me/sessions")
    public RevokedSessionsResponse revokeAllSessions() {
        int revoked = managementService.revokeAllSessions(
                currentUserAccessor.requiredCurrentUser().userId());
        return new RevokedSessionsResponse(revoked);
    }

    public record RoleAssignmentRequest(OffsetDateTime validFrom, OffsetDateTime validUntil) { }

    public record RevokedSessionsResponse(int revokedSessions) { }
}
