package org.ocean.admin.platform.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户角色解析服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleService {

    public static final String ROLE_TYPE_ADMIN = "ADMIN";
    public static final String ROLE_TYPE_NORMAL_USER = "NORMAL_USER";
    public static final String ROLE_TYPE_REVIEWER = "REVIEWER";
    public static final String ROLE_TYPE_DATA_OPERATOR = "DATA_OPERATOR";

    public static final String PLATFORM_DATA_MANAGE = "DATA_MANAGE";
    public static final String PLATFORM_BUSINESS = "BUSINESS";

    public static final String PLATFORM_CODE_OCEAN_CLOUD = "OCEAN_CLOUD";
    public static final String PLATFORM_CODE_OCEAN_VISION = "OCEAN_VISION";
    public static final String PLATFORM_CODE_OCEAN_ANALYST = "OCEAN_ANALYST";

    private final JdbcTemplate jdbcTemplate;

    public List<String> getUserRoleCodes(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            String sql = "SELECT r.role_code FROM sys_role r " +
                    "INNER JOIN sys_user_role ur ON r.id = ur.role_id " +
                    "WHERE ur.user_id = ? AND r.deleted = 0 AND r.status = 1";
            return jdbcTemplate.queryForList(sql, String.class, userId);
        } catch (Exception e) {
            log.error("获取用户角色失败, userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public String resolveRoleType(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return ROLE_TYPE_NORMAL_USER;
        }

        if (containsAny(roleCodes, "SUPER_ADMIN", "ADMIN")) {
            return ROLE_TYPE_ADMIN;
        }
        if (containsAny(roleCodes, "DATA_OPERATOR", "DATA_STAFF", "DATA_USER")) {
            return ROLE_TYPE_DATA_OPERATOR;
        }
        if (containsAny(roleCodes, "REVIEWER", "AUDITOR")) {
            return ROLE_TYPE_REVIEWER;
        }
        if (containsAny(roleCodes, "NORMAL_USER", "USER")) {
            return ROLE_TYPE_NORMAL_USER;
        }
        return ROLE_TYPE_NORMAL_USER;
    }

    public String normalizePlatform(String rawPlatform) {
        if (rawPlatform == null || rawPlatform.isBlank()) {
            return PLATFORM_BUSINESS;
        }
        String normalized = rawPlatform.trim().toUpperCase();
        if ("BUSINESS".equals(normalized)
                || PLATFORM_CODE_OCEAN_VISION.equals(normalized)
                || "KANHAI_PLATFORM".equals(normalized)
                || "V3_KANHAI_PLATFORM".equals(normalized)) {
            return PLATFORM_BUSINESS;
        }
        if ("DATA_MANAGE".equals(normalized)
                || "BACKEND_MANAGE".equals(normalized)
                || "MANAGE".equals(normalized)
                || PLATFORM_CODE_OCEAN_CLOUD.equals(normalized)
                || PLATFORM_CODE_OCEAN_ANALYST.equals(normalized)
                || "REACT_ADMIN".equals(normalized)
                || "XIHAI_ADMIN".equals(normalized)) {
            return PLATFORM_DATA_MANAGE;
        }
        return PLATFORM_BUSINESS;
    }

    public boolean canLoginPlatform(String roleType, String platform) {
        if (ROLE_TYPE_ADMIN.equals(roleType)) {
            return true;
        }
        if (ROLE_TYPE_NORMAL_USER.equals(roleType)) {
            return PLATFORM_BUSINESS.equals(platform);
        }
        if (ROLE_TYPE_REVIEWER.equals(roleType) || ROLE_TYPE_DATA_OPERATOR.equals(roleType)) {
            return PLATFORM_DATA_MANAGE.equals(platform);
        }
        return false;
    }

    public List<String> getUserPlatformCodes(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            String sql = "SELECT p.platform_code FROM sys_platform p " +
                    "INNER JOIN sys_user_platform up ON p.id = up.platform_id " +
                    "WHERE up.user_id = ? AND p.deleted = 0 AND p.status = 1";
            return jdbcTemplate.queryForList(sql, String.class, userId);
        } catch (Exception e) {
            log.error("获取用户平台权限失败, userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    public boolean canUserLoginPlatform(Long userId, String platform) {
        List<String> platformCodes = getUserPlatformCodes(userId);
        if (platformCodes == null || platformCodes.isEmpty()) {
            // 向后兼容：历史用户未配置平台时，不额外拦截。
            return true;
        }
        String rawTarget = platform == null ? "" : platform.trim();
        if (!rawTarget.isEmpty()) {
            for (String platformCode : platformCodes) {
                if (platformCode != null && rawTarget.equalsIgnoreCase(platformCode.trim())) {
                    return true;
                }
            }
        }
        String normalizedTarget = normalizePlatform(platform);
        for (String platformCode : platformCodes) {
            if (normalizedTarget.equals(normalizePlatformByCode(platformCode))) {
                return true;
            }
        }
        return false;
    }

    public boolean canAccessAdminManagement(String roleType) {
        return ROLE_TYPE_ADMIN.equals(roleType);
    }

    public boolean canWriteData(String roleType) {
        return ROLE_TYPE_ADMIN.equals(roleType) || ROLE_TYPE_DATA_OPERATOR.equals(roleType);
    }

    public boolean canReadData(String roleType) {
        return ROLE_TYPE_ADMIN.equals(roleType)
                || ROLE_TYPE_DATA_OPERATOR.equals(roleType)
                || ROLE_TYPE_REVIEWER.equals(roleType);
    }

    private boolean containsAny(List<String> roleCodes, String... candidates) {
        for (String roleCode : roleCodes) {
            if (roleCode == null) {
                continue;
            }
            String normalized = roleCode.trim().toUpperCase();
            for (String candidate : candidates) {
                if (candidate.equals(normalized)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizePlatformByCode(String platformCode) {
        if (platformCode == null || platformCode.isBlank()) {
            return PLATFORM_BUSINESS;
        }
        String normalized = platformCode.trim().toUpperCase();
        if ("BACKEND_MANAGE".equals(normalized) || "DATA_MANAGE".equals(normalized)) {
            return PLATFORM_DATA_MANAGE;
        }
        if (PLATFORM_CODE_OCEAN_CLOUD.equals(normalized)
                || PLATFORM_CODE_OCEAN_ANALYST.equals(normalized)
                || "REACT_ADMIN".equals(normalized)
                || "XIHAI_ADMIN".equals(normalized)) {
            return PLATFORM_DATA_MANAGE;
        }
        if (PLATFORM_CODE_OCEAN_VISION.equals(normalized) || "V3_KANHAI_PLATFORM".equals(normalized)) {
            return PLATFORM_BUSINESS;
        }
        if ("KANHAI_PLATFORM".equals(normalized) || "BUSINESS".equals(normalized)) {
            return PLATFORM_BUSINESS;
        }
        return normalizePlatform(normalized);
    }
}

