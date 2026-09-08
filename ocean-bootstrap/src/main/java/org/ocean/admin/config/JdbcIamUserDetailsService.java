package org.ocean.admin.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 从 IAM 表加载 Spring Security 用户及其当前有效授权。
 * 用户名按统一规则归一化；角色以 {@code ROLE_} 前缀暴露，权限保留业务编码。
 */
@Service
public class JdbcIamUserDetailsService implements UserDetailsService {

    /** 查询未软删除用户的认证信息与账号有效期。 */
    private static final String USER_SQL = """
            SELECT id, username, password_hash, status, permanent_valid,
                   valid_from, valid_until, locked_until
              FROM ocean_platform.iam_user
             WHERE username_normalized = ?
               AND deleted_at IS NULL
            """;

    /**
     * 合并用户当前有效的角色和权限。
     * UNION 会去重，时间条件用于排除尚未生效或已经到期的角色分配。
     */
    private static final String AUTHORITY_SQL = """
            SELECT DISTINCT authority
              FROM (
                    SELECT 'ROLE_' || r.role_code AS authority
                      FROM ocean_platform.iam_user_role ur
                      JOIN ocean_platform.iam_role r ON r.id = ur.role_id
                     WHERE ur.user_id = ?
                       AND r.status = 'ENABLED'
                       AND (ur.valid_from IS NULL OR ur.valid_from <= now())
                       AND (ur.valid_until IS NULL OR ur.valid_until > now())
                    UNION
                    SELECT p.permission_code AS authority
                      FROM ocean_platform.iam_user_role ur
                      JOIN ocean_platform.iam_role r ON r.id = ur.role_id
                      JOIN ocean_platform.iam_role_permission rp ON rp.role_id = r.id
                      JOIN ocean_platform.iam_permission p ON p.id = rp.permission_id
                     WHERE ur.user_id = ?
                       AND r.status = 'ENABLED'
                       AND p.status = 'ENABLED'
                       AND (ur.valid_from IS NULL OR ur.valid_from <= now())
                       AND (ur.valid_until IS NULL OR ur.valid_until > now())
                   ) granted
             ORDER BY authority
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcIamUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 将数据库状态映射为 Spring Security 的账号可用、过期和锁定语义。 */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        UserRecord user = jdbcTemplate.query(USER_SQL, resultSet ->
                resultSet.next() ? mapUser(resultSet) : null, normalized);
        if (user == null) {
            throw new UsernameNotFoundException("User not found");
        }

        List<SimpleGrantedAuthority> authorities = jdbcTemplate.queryForList(
                        AUTHORITY_SQL, String.class, user.id(), user.id())
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        // 在一次加载中复用同一时刻，避免临界时间上各状态判断不一致。
        OffsetDateTime now = OffsetDateTime.now();
        boolean enabled = "ACTIVE".equals(user.status())
                && (user.validFrom() == null || !user.validFrom().isAfter(now));
        boolean accountNonExpired = user.permanentValid()
                || user.validUntil() == null
                || user.validUntil().isAfter(now);
        boolean accountNonLocked = !"LOCKED".equals(user.status())
                && (user.lockedUntil() == null || !user.lockedUntil().isAfter(now));

        return User.withUsername(user.username())
                .password(user.passwordHash())
                .authorities(authorities)
                .disabled(!enabled)
                .accountExpired(!accountNonExpired)
                .accountLocked(!accountNonLocked)
                .credentialsExpired(false)
                .build();
    }

    /** 将当前结果行转换为仅供本服务内部使用的用户快照。 */
    private static UserRecord mapUser(ResultSet resultSet) throws SQLException {
        return new UserRecord(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                resultSet.getString("status"),
                resultSet.getBoolean("permanent_valid"),
                resultSet.getObject("valid_from", OffsetDateTime.class),
                resultSet.getObject("valid_until", OffsetDateTime.class),
                resultSet.getObject("locked_until", OffsetDateTime.class));
    }

    /** IAM 用户查询所需的最小字段集合。 */
    private record UserRecord(
            java.util.UUID id,
            String username,
            String passwordHash,
            String status,
            boolean permanentValid,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            OffsetDateTime lockedUntil) {
    }
}
