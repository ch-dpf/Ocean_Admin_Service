package org.ocean.admin.platform.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.ocean.admin.platform.identity.dao.entity.IamAuthSessionEntity;
import org.ocean.admin.platform.identity.dao.entity.IamPermissionEntity;
import org.ocean.admin.platform.identity.dao.entity.IamRoleEntity;
import org.ocean.admin.platform.identity.dao.entity.IamUserEntity;
import org.ocean.admin.platform.identity.dao.entity.IamUserRoleEntity;
import org.ocean.admin.platform.identity.dao.mapper.IamAuthSessionMapper;
import org.ocean.admin.platform.identity.dao.mapper.IamPermissionMapper;
import org.ocean.admin.platform.identity.dao.mapper.IamRoleMapper;
import org.ocean.admin.platform.identity.dao.mapper.IamUserMapper;
import org.ocean.admin.platform.identity.dao.mapper.IamUserRoleMapper;
import org.ocean.admin.platform.identity.session.RefreshTokenLifecycleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 用户、授权和会话 Web API 的应用服务边界。 */
@Service
@Transactional(readOnly = true)
public class IdentityManagementService {

    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED", "LOCKED");

    private final IamUserMapper userMapper;
    private final IamRoleMapper roleMapper;
    private final IamPermissionMapper permissionMapper;
    private final IamUserRoleMapper userRoleMapper;
    private final IamAuthSessionMapper sessionMapper;
    private final RefreshTokenLifecycleService lifecycleService;

    public IdentityManagementService(
            IamUserMapper userMapper,
            IamRoleMapper roleMapper,
            IamPermissionMapper permissionMapper,
            IamUserRoleMapper userRoleMapper,
            IamAuthSessionMapper sessionMapper,
            RefreshTokenLifecycleService lifecycleService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.sessionMapper = sessionMapper;
        this.lifecycleService = lifecycleService;
    }

    public PageResult<UserView> users(int page, int size, String keyword, String status) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 1 and size must be between 1 and 100");
        }
        String normalizedKeyword = normalizedKeyword(keyword);
        String normalizedStatus = normalizedStatus(status);
        LambdaQueryWrapper<IamUserEntity> criteria = new LambdaQueryWrapper<IamUserEntity>()
                .isNull(IamUserEntity::getDeletedAt)
                .eq(normalizedStatus != null, IamUserEntity::getStatus, normalizedStatus)
                .and(normalizedKeyword != null, nested -> nested
                        .like(IamUserEntity::getUsername, normalizedKeyword)
                        .or().like(IamUserEntity::getRealName, normalizedKeyword)
                        .or().like(IamUserEntity::getEmail, normalizedKeyword));
        long total = userMapper.selectCount(criteria);
        long offset = (long) (page - 1) * size;
        List<UserView> records = userMapper.selectList(criteria.clone()
                        .orderByDesc(IamUserEntity::getCreatedAt)
                        .last("LIMIT " + size + " OFFSET " + offset))
                .stream().map(UserView::from).toList();
        return new PageResult<>(records, page, size, total);
    }

    public UserView user(UUID userId) {
        return UserView.from(requiredUser(userId));
    }

    public List<RoleView> roles() {
        return roleMapper.selectList(new LambdaQueryWrapper<IamRoleEntity>()
                        .orderByAsc(IamRoleEntity::getScopeType, IamRoleEntity::getRoleCode))
                .stream().map(RoleView::from).toList();
    }

    public List<PermissionView> permissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<IamPermissionEntity>()
                        .orderByAsc(IamPermissionEntity::getPermissionCode))
                .stream().map(PermissionView::from).toList();
    }

    public List<RoleAssignmentView> userRoles(UUID userId) {
        requiredUser(userId);
        List<IamUserRoleEntity> assignments = userRoleMapper.selectList(
                new LambdaQueryWrapper<IamUserRoleEntity>()
                        .eq(IamUserRoleEntity::getUserId, userId));
        if (assignments.isEmpty()) {
            return List.of();
        }
        var rolesById = roleMapper.selectByIds(
                        assignments.stream().map(IamUserRoleEntity::getRoleId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(IamRoleEntity::getId, role -> role));
        return assignments.stream()
                .filter(assignment -> rolesById.containsKey(assignment.getRoleId()))
                .map(assignment -> RoleAssignmentView.from(
                        assignment, rolesById.get(assignment.getRoleId())))
                .toList();
    }

    @Transactional
    public RoleAssignmentView assignRole(
            UUID userId, UUID roleId, OffsetDateTime validFrom, OffsetDateTime validUntil) {
        requiredUser(userId);
        IamRoleEntity role = requiredRole(roleId);
        if (!"ENABLED".equals(role.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Disabled role cannot be assigned");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "validUntil must be later than validFrom");
        }
        IamUserRoleEntity existing = userRoleMapper.selectOne(
                new LambdaQueryWrapper<IamUserRoleEntity>()
                        .eq(IamUserRoleEntity::getUserId, userId)
                        .eq(IamUserRoleEntity::getRoleId, roleId));
        if (existing != null) {
            return RoleAssignmentView.from(existing, role);
        }
        IamUserRoleEntity assignment = new IamUserRoleEntity();
        assignment.setUserId(userId);
        assignment.setRoleId(roleId);
        assignment.setPlatformId("PLATFORM".equals(role.getScopeType()) ? role.getPlatformId() : null);
        assignment.setValidFrom(validFrom);
        assignment.setValidUntil(validUntil);
        userRoleMapper.insert(assignment);
        return RoleAssignmentView.from(assignment, role);
    }

    @Transactional
    public void revokeRole(UUID actorUserId, UUID userId, UUID roleId) {
        IamRoleEntity role = requiredRole(roleId);
        if (actorUserId.equals(userId) && "SUPER_ADMIN".equals(role.getRoleCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The current user cannot revoke their own SUPER_ADMIN role");
        }
        int deleted = userRoleMapper.delete(new LambdaQueryWrapper<IamUserRoleEntity>()
                .eq(IamUserRoleEntity::getUserId, userId)
                .eq(IamUserRoleEntity::getRoleId, roleId));
        if (deleted == 0) {
            throw notFound("Role assignment not found");
        }
    }

    public List<SessionView> activeSessions(UUID userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<IamAuthSessionEntity>()
                        .eq(IamAuthSessionEntity::getUserId, userId)
                        .isNull(IamAuthSessionEntity::getRevokedAt)
                        .gt(IamAuthSessionEntity::getExpiresAt, OffsetDateTime.now())
                        .orderByDesc(IamAuthSessionEntity::getLastActiveAt))
                .stream().map(SessionView::from).toList();
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        IamAuthSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId()) || session.getRevokedAt() != null) {
            throw notFound("Active session not found");
        }
        lifecycleService.revokeSession(sessionId, "USER_LOGOUT");
    }

    @Transactional
    public int revokeAllSessions(UUID userId) {
        List<UUID> sessionIds = sessionMapper.selectList(
                        new LambdaQueryWrapper<IamAuthSessionEntity>()
                                .select(IamAuthSessionEntity::getSessionId)
                                .eq(IamAuthSessionEntity::getUserId, userId)
                                .isNull(IamAuthSessionEntity::getRevokedAt))
                .stream().map(IamAuthSessionEntity::getSessionId).toList();
        sessionIds.forEach(sessionId -> lifecycleService.revokeSession(sessionId, "USER_LOGOUT_ALL"));
        return sessionIds.size();
    }

    private IamUserEntity requiredUser(UUID userId) {
        IamUserEntity user = userMapper.selectOne(new LambdaQueryWrapper<IamUserEntity>()
                .eq(IamUserEntity::getId, userId)
                .isNull(IamUserEntity::getDeletedAt));
        if (user == null) {
            throw notFound("User not found");
        }
        return user;
    }

    private IamRoleEntity requiredRole(UUID roleId) {
        IamRoleEntity role = roleMapper.selectById(roleId);
        if (role == null) {
            throw notFound("Role not found");
        }
        return role;
    }

    private static String normalizedKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String value = keyword.strip();
        if (value.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword is too long");
        }
        return value;
    }

    private static String normalizedStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.strip().toUpperCase(Locale.ROOT);
        if (!USER_STATUSES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user status");
        }
        return value;
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    public record PageResult<T>(List<T> records, int page, int size, long total) { }

    public record UserView(
            UUID id, String username, String realName, String email, String phone,
            String avatarUrl, String status, OffsetDateTime validFrom,
            OffsetDateTime validUntil, boolean permanentValid, int maxLoginDevices,
            OffsetDateTime lastLoginAt, String lastLoginIp,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {
        private static UserView from(IamUserEntity user) {
            return new UserView(user.getId(), user.getUsername(), user.getRealName(),
                    user.getEmail(), user.getPhone(), user.getAvatarUrl(), user.getStatus(),
                    user.getValidFrom(), user.getValidUntil(), Boolean.TRUE.equals(user.getPermanentValid()),
                    user.getMaxLoginDevices() == null ? 1 : user.getMaxLoginDevices(),
                    user.getLastLoginAt(), user.getLastLoginIp(), user.getCreatedAt(),
                    user.getUpdatedAt(), user.getVersion() == null ? 0 : user.getVersion());
        }
    }

    public record RoleView(
            UUID id, UUID platformId, String roleCode, String roleName,
            String scopeType, String status, String description) {
        private static RoleView from(IamRoleEntity role) {
            return new RoleView(role.getId(), role.getPlatformId(), role.getRoleCode(),
                    role.getRoleName(), role.getScopeType(), role.getStatus(), role.getDescription());
        }
    }

    public record PermissionView(
            UUID id, UUID platformId, String permissionCode, String permissionName,
            String resource, String action, String status, String description) {
        private static PermissionView from(IamPermissionEntity permission) {
            return new PermissionView(permission.getId(), permission.getPlatformId(),
                    permission.getPermissionCode(), permission.getPermissionName(),
                    permission.getResource(), permission.getAction(), permission.getStatus(),
                    permission.getDescription());
        }
    }

    public record RoleAssignmentView(
            UUID roleId, String roleCode, String roleName, UUID platformId,
            OffsetDateTime validFrom, OffsetDateTime validUntil) {
        private static RoleAssignmentView from(IamUserRoleEntity assignment, IamRoleEntity role) {
            return new RoleAssignmentView(role.getId(), role.getRoleCode(), role.getRoleName(),
                    assignment.getPlatformId(), assignment.getValidFrom(), assignment.getValidUntil());
        }
    }

    public record SessionView(
            UUID sessionId, UUID platformId, String deviceId, String deviceName,
            String clientIp, String userAgent, OffsetDateTime createdAt,
            OffsetDateTime lastActiveAt, OffsetDateTime expiresAt,
            OffsetDateTime refreshExpiresAt, long version) {
        private static SessionView from(IamAuthSessionEntity session) {
            return new SessionView(session.getSessionId(), session.getPlatformId(),
                    session.getDeviceId(), session.getDeviceName(), session.getClientIp(),
                    session.getUserAgent(), session.getCreatedAt(), session.getLastActiveAt(),
                    session.getExpiresAt(), session.getRefreshExpiresAt(),
                    session.getVersion() == null ? 0 : session.getVersion());
        }
    }
}
