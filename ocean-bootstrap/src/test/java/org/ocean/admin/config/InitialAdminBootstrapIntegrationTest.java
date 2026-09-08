package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 PostgreSQL 上验证首位管理员初始化的安全边界与幂等性。 */
@Testcontainers(disabledWithoutDocker = true)
class InitialAdminBootstrapIntegrationTest {

    private static final String VALID_PASSWORD = "StrongPass9!";

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;
    private static PasswordEncoder passwordEncoder;

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
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @BeforeEach
    void removeUsers() {
        jdbcTemplate.update("DELETE FROM ocean_platform.iam_user");
    }

    @Test
    void createsFirstAdministratorAndSkipsAnAlreadyInitializedAccount() throws Exception {
        char[] suppliedPassword = VALID_PASSWORD.toCharArray();
        bootstrap(true, "Admin", () -> suppliedPassword).run(arguments());
        assertThat(suppliedPassword).containsOnly('\0');

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM ocean_platform.iam_user WHERE username_normalized = 'admin'",
                String.class);
        assertThat(passwordHash).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(VALID_PASSWORD, passwordHash)).isTrue();

        UserDetails administrator = new JdbcIamUserDetailsService(jdbcTemplate)
                .loadUserByUsername(" ADMIN ");
        assertThat(administrator.getAuthorities())
                .extracting("authority")
                .contains("ROLE_SUPER_ADMIN");

        bootstrap(true, "Admin", () -> {
            throw new AssertionError("Idempotent bootstrap must not read the password again");
        }).run(arguments());
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void disabledBootstrapDoesNotReadPasswordOrCreateUser() throws Exception {
        bootstrap(false, "admin", () -> {
            throw new AssertionError("Disabled bootstrap must not read the password");
        }).run(arguments());

        assertThat(userCount()).isZero();
    }

    @Test
    void rejectsPasswordThatViolatesDatabaseSecurityPolicy() {
        InitialAdminBootstrap bootstrap = bootstrap(true, "admin", () -> "weak".toCharArray());

        assertThatThrownBy(() -> bootstrap.run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than the security policy");
        assertThat(userCount()).isZero();
    }

    @Test
    void rejectsEnabledBootstrapWithoutEnvironmentPassword() {
        InitialAdminBootstrap bootstrap = bootstrap(true, "admin", () -> null);

        assertThatThrownBy(() -> bootstrap.run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EnvironmentAdminPasswordSource.PASSWORD_ENVIRONMENT_VARIABLE);
        assertThat(userCount()).isZero();
    }

    @Test
    void refusesToCreateAdministratorWhenIamAlreadyContainsAnotherUser() {
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_user (
                    id, username, username_normalized, password_hash, status
                ) VALUES (?, 'existing', 'existing', '{noop}not-used', 'ACTIVE')
                """, UUID.randomUUID());

        InitialAdminBootstrap bootstrap = bootstrap(true, "admin", () -> VALID_PASSWORD.toCharArray());
        assertThatThrownBy(() -> bootstrap.run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM already contains users");
        assertThat(userCount()).isEqualTo(1);
    }

    private static InitialAdminBootstrap bootstrap(
            boolean enabled, String username, AdminPasswordSource passwordSource) {
        return new InitialAdminBootstrap(
                jdbcTemplate,
                transactionManager,
                passwordEncoder,
                new BootstrapAdminProperties(enabled, username),
                passwordSource);
    }

    private static DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments(new String[0]);
    }

    private static long userCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ocean_platform.iam_user", Long.class);
        return count == null ? 0 : count;
    }
}
