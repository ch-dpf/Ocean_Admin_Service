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

@Service
public class JdbcIamUserDetailsService implements UserDetailsService {

    private static final String USER_SQL = """
            SELECT id, username, password_hash, status, permanent_valid,
                   valid_from, valid_until, locked_until
              FROM ocean_platform.iam_user
             WHERE username_normalized = ?
               AND deleted_at IS NULL
            """;

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
