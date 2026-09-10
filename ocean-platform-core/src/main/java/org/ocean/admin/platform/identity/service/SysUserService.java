package org.ocean.admin.platform.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;

import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.platform.audit.entity.SysLoginLog;
import org.ocean.admin.platform.audit.service.SysLoginLogService;
import org.ocean.admin.platform.identity.entity.SysUser;
import org.ocean.admin.platform.identity.mapper.SysUserMapper;
import org.ocean.admin.platform.identity.vo.UserOnlineDeviceVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SysUserService {

    private final SysUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final UserRoleService userRoleService;
    private final SecurityPolicyService securityPolicyService;
    private final AuthUserSessionService authUserSessionService;
    private final SysLoginLogService loginLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public PageResult<List<SysUser>> queryUsers(String username, Integer status, Integer current, Integer size) {
        Page<SysUser> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getDeleted, 0);
        
        if (username != null && !username.isEmpty()) {
            wrapper.like(SysUser::getUsername, username);
        }
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }
        wrapper.orderByDesc(SysUser::getCreateTime);
        
        Page<SysUser> result = userMapper.selectPage(page, wrapper);
        
        PageResult<List<SysUser>> pageResult = new PageResult<>();
        pageResult.setCurrent(result.getCurrent());
        pageResult.setSize(result.getSize());
        pageResult.setTotal(result.getTotal());
        List<SysUser> records = result.getRecords();
        fillRoleInfo(records);
        fillPlatformInfo(records);
        fillOnlineDeviceInfo(records);
        pageResult.setRecords(records);
        return pageResult;
    }

    public List<UserOnlineDeviceVO> listOnlineDevices(Long userId) {
        List<AuthUserSessionService.ActiveDeviceSession> activeDevices = authUserSessionService.listActiveDevices(userId);
        if (activeDevices.isEmpty()) {
            return List.of();
        }

        List<String> sessionIds = activeDevices.stream()
                .map(AuthUserSessionService.ActiveDeviceSession::getSessionId)
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .toList();
        Map<String, SysLoginLog> latestLogMap = loginLogService.getLatestLoginLogsBySessionIds(sessionIds);

        return activeDevices.stream()
                .map(item -> toOnlineDeviceVO(item, latestLogMap.get(item.getSessionId())))
                .sorted(Comparator
                        .comparing(UserOnlineDeviceVO::getLastActiveTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UserOnlineDeviceVO::getLoginTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public boolean kickoutOnlineDevice(Long userId, String sessionId) {
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new RuntimeException("会话ID不能为空");
        }

        String normalizedSessionId = sessionId.trim();
        authUserSessionService.removeSession(userId, normalizedSessionId);
        loginLogService.updateLogoutTime(normalizedSessionId, LocalDateTime.now());
        return true;
    }

    @Transactional
    public boolean createUser(SysUser user) {
        applyLoginPolicyDefaults(user, false);
        if (user.getPassword() != null) {
            securityPolicyService.validatePasswordComplexity(user.getPassword());
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        boolean inserted = userMapper.insert(user) > 0;
        if (!inserted) {
            return false;
        }

        List<String> roleCodes = sanitizeRoleCodes(user.getRoleCodes());
        if (roleCodes.isEmpty()) {
            roleCodes = List.of("NORMAL_USER");
        }
        bindUserRoles(user.getId(), roleCodes);

        List<String> platformCodes = sanitizePlatformCodes(user.getPlatformCodes());
        if (platformCodes.isEmpty()) {
            platformCodes = List.of("OCEAN_VISION");
        }
        bindUserPlatforms(user.getId(), platformCodes);
        return true;
    }

    @Transactional
    public boolean updateUser(SysUser user) {
        applyLoginPolicyDefaults(user, true);
        // 更新时不修改密码
        user.setPassword(null);
        boolean updated = userMapper.updateById(user) > 0;
        if (!updated) {
            return false;
        }

        if (user.getRoleCodes() != null) {
            List<String> roleCodes = sanitizeRoleCodes(user.getRoleCodes());
            if (roleCodes.isEmpty()) {
                roleCodes = List.of("NORMAL_USER");
            }
            bindUserRoles(user.getId(), roleCodes);
        }
        if (user.getPlatformCodes() != null) {
            List<String> platformCodes = sanitizePlatformCodes(user.getPlatformCodes());
            if (platformCodes.isEmpty()) {
                platformCodes = List.of("OCEAN_VISION");
            }
            bindUserPlatforms(user.getId(), platformCodes);
        }
        return true;
    }

    @Transactional
    public boolean deleteUser(Long id) {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM sys_user_platform WHERE user_id = ?", id);
        return userMapper.deleteById(id) > 0;
    }

    @Transactional
    public boolean resetPassword(Long id, String newPassword) {
        securityPolicyService.validatePasswordComplexity(newPassword);
        SysUser user = new SysUser();
        user.setId(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        return userMapper.updateById(user) > 0;
    }

    @Transactional
    public boolean unlockUser(Long id, String operatorName) {
        return securityPolicyService.manualUnlock(id, operatorName);
    }

    private void fillRoleInfo(List<SysUser> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        List<Long> userIds = users.stream().map(SysUser::getId).filter(id -> id != null).toList();
        if (userIds.isEmpty()) {
            return;
        }

        String inClause = userIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        String sql = "SELECT ur.user_id, array_agg(r.role_code) AS role_codes " +
                "FROM ocean_platform.sys_user_role ur " +
                "INNER JOIN ocean_platform.sys_role r ON ur.role_id = r.id " +
                "WHERE ur.user_id IN (" + inClause + ") AND r.deleted = 0 " +
                "GROUP BY ur.user_id";

        Map<Long, List<String>> roleMap = new HashMap<>();
        jdbcTemplate.query(sql, (ResultSet rs) -> {
            Long userId = rs.getLong("user_id");
            java.sql.Array roleCodesArray = rs.getArray("role_codes");
            List<String> roleCodes = new ArrayList<>();
            if (roleCodesArray != null) {
                Object arr = roleCodesArray.getArray();
                if (arr instanceof Object[] values) {
                    for (Object value : values) {
                        if (value != null) {
                            roleCodes.add(String.valueOf(value));
                        }
                    }
                }
            }
            roleMap.put(userId, roleCodes);
        });

        for (SysUser user : users) {
            List<String> roleCodes = roleMap.getOrDefault(user.getId(), List.of("NORMAL_USER"));
            user.setRoleCodes(roleCodes);
            user.setRoleType(userRoleService.resolveRoleType(roleCodes));
        }
    }

    private void fillPlatformInfo(List<SysUser> users) {
        if (users == null || users.isEmpty()) {
            return;
        }
        List<Long> userIds = users.stream().map(SysUser::getId).filter(id -> id != null).toList();
        if (userIds.isEmpty()) {
            return;
        }

        String inClause = userIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        String sql = "SELECT up.user_id, array_agg(p.platform_code) AS platform_codes " +
                "FROM ocean_platform.sys_user_platform up " +
                "INNER JOIN ocean_platform.sys_platform p ON up.platform_id = p.id " +
                "WHERE up.user_id IN (" + inClause + ") AND p.deleted = 0 AND p.status = 1 " +
                "GROUP BY up.user_id";

        Map<Long, List<String>> platformMap = new HashMap<>();
        jdbcTemplate.query(sql, (ResultSet rs) -> {
            Long userId = rs.getLong("user_id");
            java.sql.Array platformCodesArray = rs.getArray("platform_codes");
            List<String> platformCodes = new ArrayList<>();
            if (platformCodesArray != null) {
                Object arr = platformCodesArray.getArray();
                if (arr instanceof Object[] values) {
                    for (Object value : values) {
                        if (value != null) {
                            platformCodes.add(String.valueOf(value));
                        }
                    }
                }
            }
            platformMap.put(userId, platformCodes);
        });

        for (SysUser user : users) {
            List<String> platformCodes = platformMap.getOrDefault(user.getId(), List.of("OCEAN_VISION"));
            user.setPlatformCodes(platformCodes);
        }
    }

    private void fillOnlineDeviceInfo(List<SysUser> users) {
        if (users == null || users.isEmpty()) {
            return;
        }
        for (SysUser user : users) {
            if (user.getId() == null) {
                user.setOnlineDeviceCount(0);
                continue;
            }
            user.setOnlineDeviceCount(authUserSessionService.countActiveSessions(user.getId()));
        }
    }

    private UserOnlineDeviceVO toOnlineDeviceVO(AuthUserSessionService.ActiveDeviceSession session, SysLoginLog loginLog) {
        UserOnlineDeviceVO vo = new UserOnlineDeviceVO();
        vo.setSessionId(session.getSessionId());
        vo.setDeviceId(session.getDeviceId());
        vo.setPlatform(loginLog != null ? loginLog.getPlatform() : null);
        vo.setLastActiveTime(session.getLastActiveTime());
        vo.setTtlSeconds(session.getTtlSeconds());
        if (loginLog != null) {
            vo.setIpAddress(loginLog.getIpAddress());
            vo.setBrowser(loginLog.getBrowser());
            vo.setOs(loginLog.getOs());
            vo.setLoginTime(loginLog.getLoginTime());
        }
        vo.setDeviceName(resolveDeviceName(session.getDeviceId(), loginLog));
        return vo;
    }

    private String resolveDeviceName(String deviceId, SysLoginLog loginLog) {
        if (loginLog != null) {
            String browser = loginLog.getBrowser();
            String os = loginLog.getOs();
            if (browser != null && !browser.isBlank() && os != null && !os.isBlank()) {
                return browser + " / " + os;
            }
            if (os != null && !os.isBlank()) {
                return os;
            }
            if (browser != null && !browser.isBlank()) {
                return browser;
            }
        }
        if (deviceId == null || deviceId.isBlank()) {
            return "未知设备";
        }
        return deviceId.length() > 24 ? deviceId.substring(0, 24) + "..." : deviceId;
    }

    private List<String> sanitizeRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }

        List<String> sanitized = new ArrayList<>();
        for (String roleCode : roleCodes) {
            if (roleCode == null || roleCode.isBlank()) {
                continue;
            }
            String normalized = roleCode.trim().toUpperCase();
            if (!sanitized.contains(normalized)) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }

    private List<String> sanitizePlatformCodes(List<String> platformCodes) {
        if (platformCodes == null || platformCodes.isEmpty()) {
            return List.of();
        }

        List<String> sanitized = new ArrayList<>();
        for (String platformCode : platformCodes) {
            if (platformCode == null || platformCode.isBlank()) {
                continue;
            }
            String normalized = platformCode.trim().toUpperCase();
            if (!sanitized.contains(normalized)) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }

    private void bindUserRoles(Long userId, List<String> roleCodes) {
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }

        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (String roleCode : roleCodes) {
            Integer updated = jdbcTemplate.update(
                    "INSERT INTO sys_user_role (user_id, role_id, create_time) " +
                            "SELECT ?, id, NOW() FROM sys_role WHERE role_code = ? AND deleted = 0",
                    userId, roleCode
            );
            if (updated <= 0) {
                throw new RuntimeException("角色不存在或不可用: " + roleCode);
            }
        }
    }

    private void bindUserPlatforms(Long userId, List<String> platformCodes) {
        if (userId == null) {
            throw new RuntimeException("用户ID不能为空");
        }

        jdbcTemplate.update("DELETE FROM sys_user_platform WHERE user_id = ?", userId);
        for (String platformCode : platformCodes) {
            Integer updated = jdbcTemplate.update(
                    "INSERT INTO sys_user_platform (user_id, platform_id, create_time) " +
                            "SELECT ?, id, NOW() FROM sys_platform WHERE platform_code = ? AND deleted = 0 AND status = 1",
                    userId, platformCode
            );
            if (updated <= 0) {
                throw new RuntimeException("平台不存在或不可用: " + platformCode);
            }
        }
    }

    private void applyLoginPolicyDefaults(SysUser user, boolean partialUpdate) {
        if (user == null) {
            return;
        }

        Integer maxDevices = user.getMaxLoginDevices();
        if (!partialUpdate || maxDevices != null) {
            if (maxDevices == null || maxDevices <= 0) {
                user.setMaxLoginDevices(1);
            } else {
                user.setMaxLoginDevices(Math.min(maxDevices, 10));
            }
        }

        Integer permanent = user.getIsPermanentValid();
        if (!partialUpdate || permanent != null) {
            user.setIsPermanentValid(permanent != null && permanent == 1 ? 1 : 0);
        }

        if (user.getIsPermanentValid() != null && user.getIsPermanentValid() == 1) {
            user.setValidFrom(null);
            user.setValidTo(null);
            return;
        }

        if (user.getValidFrom() != null && user.getValidTo() != null && user.getValidTo().isBefore(user.getValidFrom())) {
            throw new RuntimeException("有效期结束时间不能早于开始时间");
        }
    }
}
