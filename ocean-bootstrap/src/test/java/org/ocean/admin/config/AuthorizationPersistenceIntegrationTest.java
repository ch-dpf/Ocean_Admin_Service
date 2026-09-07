package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationPersistenceIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private RegisteredClientRepository repository;

    @BeforeAll
    void migrateAndCreateRepository() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        repository = new AuthorizationPersistenceConfiguration()
                .registeredClientRepository(dataSource);
    }

    @Test
    void savesAndLoadsPkceClientUsingOfficialJdbcRepository() {
        RegisteredClient expected = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("ocean-admin-web")
                .clientName("Ocean Admin Web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:3000/login/oauth2/code/ocean-admin")
                .postLogoutRedirectUri("http://127.0.0.1:3000/")
                .scope("openid")
                .scope("profile")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        repository.save(expected);

        RegisteredClient byId = repository.findById(expected.getId());
        RegisteredClient byClientId = repository.findByClientId(expected.getClientId());

        assertThat(byId).isNotNull();
        assertThat(byClientId).isNotNull();
        assertThat(byClientId.getId()).isEqualTo(expected.getId());
        assertThat(byClientId.getRedirectUris()).containsExactlyElementsOf(expected.getRedirectUris());
        assertThat(byClientId.getPostLogoutRedirectUris())
                .containsExactlyElementsOf(expected.getPostLogoutRedirectUris());
        assertThat(byClientId.getScopes()).containsExactlyInAnyOrderElementsOf(expected.getScopes());
        assertThat(byClientId.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(byClientId.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(byClientId.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(Duration.ofMinutes(15));
    }
}
