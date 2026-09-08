package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 在真实 PostgreSQL 容器中验证 Flyway 迁移、IAM 查询和 OAuth2 JDBC 服务能够协同工作。
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationPersistenceIntegrationTest {

    /** 所有用例共享一个数据库容器，容器不可用时由 Testcontainers 自动跳过。 */
    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static RegisteredClientRepository repository;
    private static OAuth2AuthorizationService authorizationService;
    private static OAuth2AuthorizationConsentService consentService;
    private static JdbcTemplate jdbcTemplate;

    /** 执行完整迁移，并用与生产配置相同的方式创建 JDBC 持久化组件。 */
    @BeforeAll
    static void migrateAndCreateRepository() {
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
        AuthorizationPersistenceConfiguration persistence = new AuthorizationPersistenceConfiguration();
        AuthorizationPersistenceConfiguration.AuthorizationJdbcOperations operations =
                persistence.authorizationJdbcOperations(
                        dataSource, new DataSourceTransactionManager(dataSource));
        repository = persistence.registeredClientRepository(operations);
        authorizationService = persistence.jdbcOAuth2AuthorizationService(operations, repository);
        consentService = persistence.authorizationConsentService(operations, repository);
    }

    /** 验证公共客户端、PKCE、重定向地址和令牌策略可被官方仓储无损往返。 */
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

    /** 验证用户名归一化以及当前有效角色、权限到 GrantedAuthority 的映射。 */
    @Test
    void loadsActiveIamUserWithRolesAndPermissions() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_user (
                    id, username, username_normalized, password_hash, status
                ) VALUES (?, ?, ?, ?, 'ACTIVE')
                """, userId, "Alice", "alice", "{noop}test-password");
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_user_role (user_id, role_id, platform_id)
                VALUES (?, '20000000-0000-0000-0000-000000000002',
                           '10000000-0000-0000-0000-000000000001')
                """, userId);

        UserDetails user = new JdbcIamUserDetailsService(jdbcTemplate)
                .loadUserByUsername(" ALICE ");

        assertThat(user.getUsername()).isEqualTo("Alice");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.getAuthorities())
                .extracting("authority")
                .contains("ROLE_PLATFORM_ADMIN", "admin:user:read", "admin:user:write");
    }

    /** 验证访问令牌、刷新令牌和用户授权同意均可写入并按官方接口查回。 */
    @Test
    void persistsAuthorizationTokensAndConsentUsingSecurity71JdbcServices() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("jdbc-contract-" + UUID.randomUUID())
                .clientName("JDBC Contract Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example.test/callback")
                .scope("openid")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build();
        repository.save(client);

        Instant issuedAt = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "access-" + UUID.randomUUID(),
                issuedAt,
                issuedAt.plus(Duration.ofMinutes(15)),
                Set.of("openid"));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                "refresh-" + UUID.randomUUID(),
                issuedAt,
                issuedAt.plus(Duration.ofDays(7)));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
        authorizationService.save(authorization);

        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
                .withId(client.getId(), "alice")
                .scope("openid")
                .build();
        consentService.save(consent);

        assertThat(authorizationService.findById(authorization.getId())).isNotNull();
        assertThat(authorizationService.findByToken(
                        accessToken.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN))
                .extracting(OAuth2Authorization::getId)
                .isEqualTo(authorization.getId());
        assertThat(consentService.findById(client.getId(), "alice"))
                .isEqualTo(consent);
    }
}
