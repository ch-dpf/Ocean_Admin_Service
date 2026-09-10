package org.ocean.admin.platform.identity.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.platform.identity.entity.SysUser;
import org.ocean.admin.platform.identity.entity.SysUserLockRecord;
import org.ocean.admin.platform.identity.mapper.SysUserLockRecordMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 安全策略
 * @author DeepOcean
 * @since 2026-09-09
 */

@Service
@RequiredArgsConstructor
public class SecurityPolicyService {

    private static final Long POLICY_ID = 1L;

    private final JdbcTemplate jdbcTemplate;
    private final SysUserLockRecordMapper lockRecordMapper;


    public Map<String, Object> getPolicy(){
        ensurePolicyRow();
        return jdbcTemplate.queryForObject(
                "SELECT min_password_length, require_uppercase, require_lowercase, require_digit, require_special, max_error_count, lock_enabled, lock_base_minutes, lock_max_minutes " +
                        "FROM ocean_platform.sys_security_policy WHERE id = ?",
                (rs, rowNum) -> {
                    Map<String, Object> policy = new HashMap<>();
                    policy.put("minPasswordLength", rs.getInt("min_password_length"));
                    policy.put("requireUppercase", rs.getInt("require_uppercase"));
                    policy.put("requireLowercase", rs.getInt("require_lowercase"));
                    policy.put("requireDigit", rs.getInt("require_digit"));
                    policy.put("requireSpecial", rs.getInt("require_special"));
                    policy.put("maxErrorCount", rs.getInt("max_error_count"));
                    policy.put("lockEnabled", rs.getInt("lock_enabled"));
                    policy.put("lockBaseMinutes", rs.getInt("lock_base_minutes"));
                    policy.put("lockMaxMinutes", rs.getInt("lock_max_minutes"));
                    return policy;
                },
                POLICY_ID
        );
    }

    @Transactional
    public void updatePolicy(Map<String, Object> policyRequest) {
        int minPasswordLength = parseInt(policyRequest.get("minPasswordLength"), 8, 6, 64, "密码长度需在6-64之间");
        int requireUppercase = parseBooleanInt(policyRequest.get("requireUppercase"));
        int requireLowercase = parseBooleanInt(policyRequest.get("requireLowercase"));
        int requireDigit = parseBooleanInt(policyRequest.get("requireDigit"));
        int requireSpecial = parseBooleanInt(policyRequest.get("requireSpecial"));
        int maxErrorCount = parseInt(policyRequest.get("maxErrorCount"), 5, 1, 20, "错误次数限制需在1-20之间");
        int lockEnabled = parseBooleanInt(policyRequest.get("lockEnabled"));
        int lockBaseMinutes = parseInt(policyRequest.get("lockBaseMinutes"), 5, 1, 720, "基础锁定分钟需在1-720之间");
        int lockMaxMinutes = parseInt(policyRequest.get("lockMaxMinutes"), 720, lockBaseMinutes, 1440, "最大锁定分钟需在基础分钟到1440之间");

        ensurePolicyRow();
        jdbcTemplate.update(
                "UPDATE ocean_platform.sys_security_policy SET min_password_length = ?, require_uppercase = ?, require_lowercase = ?, require_digit = ?, require_special = ?, " +
                        "max_error_count = ?, lock_enabled = ?, lock_base_minutes = ?, lock_max_minutes = ?, update_time = NOW() WHERE id = ?",
                minPasswordLength, requireUppercase, requireLowercase, requireDigit, requireSpecial,
                maxErrorCount, lockEnabled, lockBaseMinutes, lockMaxMinutes, POLICY_ID
        );
    }

    public void validatePasswordComplexity(String password) {
        if (password == null || password.isBlank()) {
            throw new RuntimeException("密码不能为空");
        }

        Map<String, Object> policy = getPolicy();
        int minLength = (int) policy.get("minPasswordLength");
        if (password.length() < minLength) {
            throw new RuntimeException("密码长度不能小于" + minLength + "位");
        }
        if (((int) policy.get("requireUppercase")) == 1 && !password.matches(".*[A-Z].*")) {
            throw new RuntimeException("密码必须包含大写字母");
        }
        if (((int) policy.get("requireLowercase")) == 1 && !password.matches(".*[a-z].*")) {
            throw new RuntimeException("密码必须包含小写字母");
        }
        if (((int) policy.get("requireDigit")) == 1 && !password.matches(".*\\d.*")) {
            throw new RuntimeException("密码必须包含数字");
        }
        if (((int) policy.get("requireSpecial")) == 1 && !password.matches(".*[^a-zA-Z0-9].*")) {
            throw new RuntimeException("密码必须包含特殊字符");
        }
    }

    public void assertUserNotLocked(SysUser user) {
        if (user == null || user.getLockUntil() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockUntil().isAfter(now)) {
            long remainMinutes = Math.max(1, Duration.between(now, user.getLockUntil()).toMinutes());
            throw new RuntimeException("账号已锁定，请" + remainMinutes + "分钟后再试");
        }
    }

    @Transactional
    public void onPasswordFailure(SysUser user) {
        if (user == null || user.getId() == null) {
            return;
        }

        Map<String, Object> policy = getPolicy();
        int maxErrorCount = (int) policy.get("maxErrorCount");
        int lockEnabled = (int) policy.get("lockEnabled");
        int lockBaseMinutes = (int) policy.get("lockBaseMinutes");
        int lockMaxMinutes = (int) policy.get("lockMaxMinutes");

        int nextFailedCount = (user.getFailedPasswordAttempts() == null ? 0 : user.getFailedPasswordAttempts()) + 1;
        int nextLevel = user.getLockLevel() == null ? 0 : user.getLockLevel();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockUntil = null;

        if (lockEnabled == 1 && nextFailedCount >= maxErrorCount) {
            nextLevel += 1;
            long lockMinutes = calculateLockMinutes(lockBaseMinutes, lockMaxMinutes, nextLevel);
            lockUntil = now.plusMinutes(lockMinutes);
            recordLockEvent(user, "LOCK", nextFailedCount, nextLevel, lockUntil, (int) lockMinutes, "登录失败次数过多，账号已锁定", null);
        }

        jdbcTemplate.update(
                "UPDATE ocean_platform.sys_user SET failed_password_attempts = ?, lock_level = ?, lock_until = ?, last_password_error_time = ?, update_time = NOW() WHERE id = ?",
                nextFailedCount,
                nextLevel,
                lockUntil,
                now,
                user.getId()
        );
    }

    @Transactional
    public void onLoginSuccess(Long userId) {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE ocean_platform.sys_user SET failed_password_attempts = 0, lock_level = 0, lock_until = NULL, last_password_error_time = NULL, update_time = NOW() WHERE id = ?",
                userId
        );
    }

    @Transactional
    public boolean manualUnlock(Long userId, String operatorName) {
        if (userId == null) {
            return false;
        }
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT id, username, real_name, failed_password_attempts, lock_level FROM ocean_platform.sys_user WHERE id = ? AND deleted = 0",
                userId
        );
        boolean updated = jdbcTemplate.update(
                "UPDATE ocean_platform.sys_user SET failed_password_attempts = 0, lock_level = 0, lock_until = NULL, last_password_error_time = NULL, " +
                        "manual_unlock_time = NOW(), manual_unlock_by = ?, update_time = NOW() WHERE id = ? AND deleted = 0",
                operatorName == null || operatorName.isBlank() ? "SYSTEM" : operatorName,
                userId
        ) > 0;
        if (updated) {
            SysUser lockUser = new SysUser();
            lockUser.setId(((Number) user.get("id")).longValue());
            lockUser.setUsername((String) user.get("username"));
            lockUser.setRealName((String) user.get("real_name"));
            Integer failedAttempts = user.get("failed_password_attempts") == null ? 0 : ((Number) user.get("failed_password_attempts")).intValue();
            Integer lockLevel = user.get("lock_level") == null ? 0 : ((Number) user.get("lock_level")).intValue();
            recordLockEvent(lockUser, "UNLOCK", failedAttempts, lockLevel, null, 0, "管理员手动解锁用户", operatorName);
        }
        return updated;
    }

    private long calculateLockMinutes(int baseMinutes, int maxMinutes, int level) {
        int shift = Math.min(Math.max(level - 1, 0), 20);
        long minutes = (long) baseMinutes * (1L << shift);
        return Math.min(minutes, maxMinutes);
    }

    private int parseBooleanInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue ? 1 : 0;
        }
        if (value instanceof Number number) {
            return number.intValue() > 0 ? 1 : 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)) ? 1 : 0;
    }

    private int parseInt(Object value, int defaultValue, int min, int max, String errorMessage) {
        int result = defaultValue;
        if (value != null) {
            try {
                result = Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ex) {
                throw new RuntimeException(errorMessage);
            }
        }
        if (result < min || result > max) {
            throw new RuntimeException(errorMessage);
        }
        return result;
    }

    private void ensurePolicyRow() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ocean_platform.sys_security_policy WHERE id = ?", Integer.class, POLICY_ID);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO ocean_platform.sys_security_policy (id, min_password_length, require_uppercase, require_lowercase, require_digit, require_special, max_error_count, lock_enabled, lock_base_minutes, lock_max_minutes, create_time, update_time) " +
                        "VALUES (?, 8, 1, 1, 1, 0, 5, 1, 5, 720, NOW(), NOW())",
                POLICY_ID
        );
    }

    private void recordLockEvent(SysUser user, String recordType, Integer failedPasswordAttempts, Integer lockLevel, LocalDateTime lockUntil, Integer lockMinutes, String message, String operatorName) {
        SysUserLockRecord record = new SysUserLockRecord();
        record.setUserId(user.getId());
        record.setUsername(user.getUsername());
        record.setRealName(user.getRealName());
        record.setRecordType(recordType);
        record.setFailedPasswordAttempts(failedPasswordAttempts == null ? 0 : failedPasswordAttempts);
        record.setLockLevel(lockLevel == null ? 0 : lockLevel);
        record.setLockUntil(lockUntil);
        record.setLockMinutes(lockMinutes == null ? 0 : lockMinutes);
        record.setMessage(message);
        record.setOperatorName(operatorName);
        lockRecordMapper.insert(record);
    }



}
