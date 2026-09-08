package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 PostgreSQL 上验证首个 OAuth 公共客户端的原子初始化和幂等边界。 */
@Testcontainers(disabledWithoutDocker = true)
class InitialOAuthClientBootstrapIntegrationTest {

    private static final String CLIENT_ID = "ocean-admin-web";
    private static final String REDIRECT_URI =
            "http://127.0.0.1:3000/login/oauth2/code/ocean-admin";

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static AuthorizationPersistenceConfiguration.AuthorizationJdbcOperations operations;
    private static RegisteredClientRepository repository;

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
        AuthorizationPersistenceConfiguration persistence = new AuthorizationPersistenceConfiguration();
        operations = persistence.authorizationJdbcOperations(
                dataSource, new DataSourceTransactionManager(dataSource));
        repository = persistence.registeredClientRepository(operations);
    }

    @BeforeEach
    void removeClients() {
        operations.jdbcTemplate().update("DELETE FROM oauth2_authorization_consent");
        operations.jdbcTemplate().update("DELETE FROM oauth2_authorization");
        operations.jdbcTemplate().update("DELETE FROM iam_oauth_redirect_uri");
        operations.jdbcTemplate().update("DELETE FROM iam_oauth_client");
        operations.jdbcTemplate().update("DELETE FROM oauth2_registered_client");
    }

    @Test
    void createsPublicPkceClientAcrossProtocolAndIamTablesAndThenSkipsIt() throws Exception {
        bootstrap(properties(true, "OCEAN_ADMIN")).run(arguments());
        bootstrap(properties(true, "OCEAN_ADMIN")).run(arguments());

        RegisteredClient client = repository.findByClientId(CLIENT_ID);
        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes())
                .containsExactlyInAnyOrder(
                        AuthorizationGrantType.AUTHORIZATION_CODE,
                        AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getRedirectUris()).containsExactly(REDIRECT_URI);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(client.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(Duration.ofDays(7));
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();

        assertThat(count("iam_oauth_client")).isEqualTo(1);
        assertThat(count("iam_oauth_redirect_uri")).isEqualTo(1);
        assertThat(count("oauth2_registered_client")).isEqualTo(1);
    }

    @Test
    void disabledBootstrapDoesNothing() throws Exception {
        bootstrap(properties(false, "OCEAN_ADMIN")).run(arguments());

        assertThat(count("oauth2_registered_client")).isZero();
        assertThat(count("iam_oauth_client")).isZero();
    }

    @Test
    void rejectsConfigurationDriftWithoutOverwritingExistingClient() throws Exception {
        bootstrap(properties(true, "OCEAN_ADMIN")).run(arguments());
        BootstrapOAuthClientProperties changed = new BootstrapOAuthClientProperties(
                true, CLIENT_ID, "Changed Name", "OCEAN_ADMIN", REDIRECT_URI,
                "http://127.0.0.1:3000/", true);

        assertThatThrownBy(() -> bootstrap(changed).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent");
        assertThat(repository.findByClientId(CLIENT_ID).getClientName()).isEqualTo("Ocean Admin Web");
    }

    @Test
    void rollsBackProtocolRecordWhenPlatformDoesNotExist() {
        assertThatThrownBy(() -> bootstrap(properties(true, "MISSING")).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one enabled");

        assertThat(count("oauth2_registered_client")).isZero();
        assertThat(count("iam_oauth_client")).isZero();
    }

    private static InitialOAuthClientBootstrap bootstrap(BootstrapOAuthClientProperties properties) {
        return new InitialOAuthClientBootstrap(operations, repository, properties);
    }

    private static BootstrapOAuthClientProperties properties(boolean enabled, String platformCode) {
        return new BootstrapOAuthClientProperties(
                enabled,
                CLIENT_ID,
                "Ocean Admin Web",
                platformCode,
                REDIRECT_URI,
                "http://127.0.0.1:3000/",
                true);
    }

    private static DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments(new String[0]);
    }

    private static long count(String table) {
        Long count = operations.jdbcTemplate().queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
