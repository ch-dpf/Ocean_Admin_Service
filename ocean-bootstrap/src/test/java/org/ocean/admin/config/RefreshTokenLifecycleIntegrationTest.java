package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ocean.admin.platform.identity.session.IssuedSession;
import org.ocean.admin.platform.identity.session.RefreshTokenLifecycleService;
import org.ocean.admin.platform.identity.session.RefreshTokenRotation;
import org.ocean.admin.platform.identity.session.RefreshTokenRotationStatus;
import org.ocean.admin.platform.identity.session.SessionIssueCommand;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 PostgreSQL 中验证刷新令牌数据库状态机、回滚语义和并发串行化。 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenLifecycleIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID PLATFORM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_user (
                    id, username, username_normalized, password_hash, status
                ) VALUES (?, 'session-user', 'session-user', '{noop}unused', 'ACTIVE')
                """, USER_ID);
    }

    @BeforeEach
    void clearSessions() {
        jdbcTemplate.update("DELETE FROM ocean_platform.iam_auth_session");
    }

    @Test
    void issuesSessionWithoutPersistingRefreshTokenPlaintext() {
        RefreshTokenLifecycleService service = serviceAt(NOW);

        IssuedSession issued = service.issue(command(NOW.plus(Duration.ofDays(30))), "initial-secret");

        assertThat(issued.version()).isZero();
        String storedHash = jdbcTemplate.queryForObject("""
                SELECT token_hash FROM ocean_platform.iam_refresh_token WHERE token_id = ?
                """, String.class, issued.refreshTokenId());
        assertThat(storedHash)
                .startsWith("sha256:")
                .doesNotContain("initial-secret");
        assertThat(tokenStatuses(issued.sessionId())).containsExactly("ISSUED");
    }

    @Test
    void rotatesCurrentTokenAndMaintainsOneActiveToken() {
        RefreshTokenLifecycleService service = serviceAt(NOW);
        IssuedSession issued = service.issue(command(NOW.plus(Duration.ofDays(30))), "token-0");

        RefreshTokenRotation rotation = service.rotate(
                "token-0", "token-1", NOW.plus(Duration.ofDays(6)));

        assertThat(rotation.status()).isEqualTo(RefreshTokenRotationStatus.ROTATED);
        assertThat(rotation.sessionId()).isEqualTo(issued.sessionId());
        assertThat(rotation.sessionVersion()).isEqualTo(1);
        assertThat(tokenStatuses(issued.sessionId())).containsExactly("USED", "ISSUED");
        assertThat(activeTokenCount(issued.sessionId())).isEqualTo(1);
    }

    @Test
    void replayOfConsumedTokenRevokesWholeFamilyAndCommitsDetection() {
        RefreshTokenLifecycleService service = serviceAt(NOW);
        IssuedSession issued = service.issue(command(NOW.plus(Duration.ofDays(30))), "token-0");
        service.rotate("token-0", "token-1", NOW.plus(Duration.ofDays(6)));

        RefreshTokenRotation replay = service.rotate(
                "token-0", "attacker-token", NOW.plus(Duration.ofDays(5)));

        assertThat(replay.status()).isEqualTo(RefreshTokenRotationStatus.REUSE_DETECTED);
        assertThat(sessionRevokeReason(issued.sessionId())).isEqualTo("REFRESH_TOKEN_REUSE");
        assertThat(tokenStatuses(issued.sessionId())).containsExactly("USED", "REVOKED");
        assertThat(activeTokenCount(issued.sessionId())).isZero();
    }

    @Test
    void expiredTokenRevokesSession() {
        RefreshTokenLifecycleService issuer = serviceAt(NOW);
        IssuedSession issued = issuer.issue(command(NOW.plus(Duration.ofDays(30))), "expired-token");

        RefreshTokenRotation result = serviceAt(NOW.plus(Duration.ofDays(8))).rotate(
                "expired-token", "unused-replacement", NOW.plus(Duration.ofDays(9)));

        assertThat(result.status()).isEqualTo(RefreshTokenRotationStatus.EXPIRED);
        assertThat(sessionRevokeReason(issued.sessionId())).isEqualTo("REFRESH_TOKEN_EXPIRED");
        assertThat(tokenStatuses(issued.sessionId())).containsExactly("REVOKED");
    }

    @Test
    void revokeSessionIsIdempotent() {
        RefreshTokenLifecycleService service = serviceAt(NOW);
        IssuedSession issued = service.issue(command(NOW.plus(Duration.ofDays(30))), "logout-token");

        assertThat(service.revokeSession(issued.sessionId(), "LOGOUT")).isTrue();
        assertThat(service.revokeSession(issued.sessionId(), "LOGOUT")).isFalse();
        assertThat(sessionRevokeReason(issued.sessionId())).isEqualTo("LOGOUT");
        assertThat(tokenStatuses(issued.sessionId())).containsExactly("REVOKED");
    }

    @Test
    void databaseRejectsTransitionBetweenTerminalStates() {
        RefreshTokenLifecycleService service = serviceAt(NOW);
        IssuedSession issued = service.issue(command(NOW.plus(Duration.ofDays(30))), "terminal-token");
        service.rotate("terminal-token", "current-token", NOW.plus(Duration.ofDays(6)));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE ocean_platform.iam_refresh_token
                   SET status = 'REVOKED', used_at = NULL, revoked_at = now()
                 WHERE token_id = ?
                """, issued.refreshTokenId()))
                .hasMessageContaining("illegal refresh token transition");
        assertThat(tokenStatuses(issued.sessionId())).containsExactly("USED", "ISSUED");
    }

    @Test
    void concurrentRefreshAllowsOneRotationThenRevokesFamilyAsReuse() throws Exception {
        RefreshTokenLifecycleService service = serviceAt(NOW);
        IssuedSession issued = service.issue(command(NOW.plus(Duration.ofDays(30))), "shared-token");
        Callable<RefreshTokenRotation> first = () -> service.rotate(
                "shared-token", "replacement-a", NOW.plus(Duration.ofDays(6)));
        Callable<RefreshTokenRotation> second = () -> service.rotate(
                "shared-token", "replacement-b", NOW.plus(Duration.ofDays(6)));

        List<RefreshTokenRotationStatus> statuses;
        try (var executor = Executors.newFixedThreadPool(2)) {
            statuses = executor.invokeAll(List.of(first, second)).stream().map(future -> {
                try {
                    return future.get().status();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
        }

        assertThat(statuses).containsExactlyInAnyOrder(
                RefreshTokenRotationStatus.ROTATED,
                RefreshTokenRotationStatus.REUSE_DETECTED);
        assertThat(sessionRevokeReason(issued.sessionId())).isEqualTo("REFRESH_TOKEN_REUSE");
        assertThat(activeTokenCount(issued.sessionId())).isZero();
    }

    private static RefreshTokenLifecycleService serviceAt(Instant instant) {
        return new RefreshTokenLifecycleService(
                jdbcTemplate, transactionManager, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static SessionIssueCommand command(Instant sessionExpiresAt) {
        return new SessionIssueCommand(
                USER_ID, PLATFORM_ID, null, "device-1", "Test Browser", "127.0.0.1",
                "integration-test", sessionExpiresAt, NOW.plus(Duration.ofDays(7)));
    }

    private static List<String> tokenStatuses(UUID sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT status FROM ocean_platform.iam_refresh_token
                 WHERE session_id = ? ORDER BY sequence_number
                """, String.class, sessionId);
    }

    private static long activeTokenCount(UUID sessionId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM ocean_platform.iam_refresh_token
                 WHERE session_id = ? AND status = 'ISSUED'
                """, Long.class, sessionId);
        return count == null ? 0 : count;
    }

    private static String sessionRevokeReason(UUID sessionId) {
        return jdbcTemplate.queryForObject("""
                SELECT revoke_reason FROM ocean_platform.iam_auth_session WHERE session_id = ?
                """, String.class, sessionId);
    }
}
