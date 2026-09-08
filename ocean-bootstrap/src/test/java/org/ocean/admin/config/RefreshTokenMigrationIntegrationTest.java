package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 验证已有 V3 会话数据能够升级为 V4 的刷新令牌族模型。 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenMigrationIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migratesLegacySessionHashFromV3ToV4() {
        Flyway v3 = flyway("3");
        v3.migrate();
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_user (
                    id, username, username_normalized, password_hash, status
                ) VALUES (?, 'legacy-session-user', 'legacy-session-user', '{noop}unused', 'ACTIVE')
                """, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_auth_session (
                    session_id, user_id, platform_id, refresh_token_hash,
                    created_at, last_active_at, expires_at, refresh_expires_at
                ) VALUES (?, ?, '10000000-0000-0000-0000-000000000001', 'sha256:legacy',
                          '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z',
                          '2026-10-01T00:00:00Z', '2026-09-15T00:00:00Z')
                """, SESSION_ID, USER_ID);

        flyway(null).migrate();

        Map<String, Object> migrated = jdbcTemplate.queryForMap("""
                SELECT token_id, session_id, token_family_id, token_hash,
                       sequence_number, status, expires_at
                  FROM ocean_platform.iam_refresh_token
                 WHERE session_id = ?
                """, SESSION_ID);
        assertThat(migrated.get("token_id")).isEqualTo(SESSION_ID);
        assertThat(migrated.get("token_family_id")).isEqualTo(SESSION_ID);
        assertThat(migrated.get("token_hash")).isEqualTo("sha256:legacy");
        assertThat(migrated.get("sequence_number")).isEqualTo(0L);
        assertThat(migrated.get("status")).isEqualTo("ISSUED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'ocean_platform'
                   AND table_name = 'iam_auth_session'
                   AND column_name = 'refresh_token_hash'
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT version FROM ocean_platform.iam_auth_session WHERE session_id = ?
                """, Long.class, SESSION_ID)).isZero();
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static JdbcTemplate jdbcTemplate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }
}
