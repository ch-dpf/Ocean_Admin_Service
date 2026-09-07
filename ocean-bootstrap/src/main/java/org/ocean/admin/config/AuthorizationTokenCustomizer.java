package org.ocean.admin.config;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final String PLATFORM_SQL = """
            SELECT p.id, p.platform_code
              FROM ocean_platform.iam_oauth_client c
              JOIN ocean_platform.iam_platform p ON p.id = c.platform_id
             WHERE c.registered_client_id = ?
               AND c.status = 'ENABLED'
               AND p.status = 'ENABLED'
               AND p.deleted_at IS NULL
            """;

    private static final String USER_AUTHORITY_SQL = """
            SELECT DISTINCT r.role_code, p.permission_code
              FROM ocean_platform.iam_user u
              JOIN ocean_platform.iam_user_role ur ON ur.user_id = u.id
              JOIN ocean_platform.iam_role r ON r.id = ur.role_id
              LEFT JOIN ocean_platform.iam_role_permission rp ON rp.role_id = r.id
              LEFT JOIN ocean_platform.iam_permission p ON p.id = rp.permission_id
             WHERE u.username_normalized = lower(?)
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
               AND r.status = 'ENABLED'
               AND (r.scope_type = 'GLOBAL' OR ur.platform_id = ?)
               AND (ur.valid_from IS NULL OR ur.valid_from <= now())
               AND (ur.valid_until IS NULL OR ur.valid_until > now())
               AND (p.id IS NULL OR p.status = 'ENABLED')
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationServerProperties properties;

    public AuthorizationTokenCustomizer(
            JdbcTemplate jdbcTemplate,
            AuthorizationServerProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        context.getClaims().audience(List.of(properties.audience()));

        Platform platform = jdbcTemplate.query(PLATFORM_SQL, resultSet ->
                        resultSet.next()
                                ? new Platform(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getString("platform_code"))
                                : null,
                context.getRegisteredClient().getId());
        if (platform == null) {
            return;
        }

        context.getClaims()
                .claim("platform_id", platform.id().toString())
                .claim("platform_code", platform.code());

        Set<String> roles = new TreeSet<>();
        Set<String> permissions = new TreeSet<>();
        jdbcTemplate.query(USER_AUTHORITY_SQL, resultSet -> {
            roles.add(resultSet.getString("role_code"));
            String permission = resultSet.getString("permission_code");
            if (permission != null) {
                permissions.add(permission);
            }
        }, context.getPrincipal().getName(), platform.id());
        context.getClaims()
                .claim("roles", roles)
                .claim("permissions", permissions);
    }

    private record Platform(UUID id, String code) {
    }
}
