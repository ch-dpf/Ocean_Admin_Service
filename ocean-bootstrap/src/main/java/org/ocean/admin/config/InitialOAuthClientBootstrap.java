package org.ocean.admin.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 原子创建首个公共 PKCE 客户端及其 IAM 平台元数据，不覆盖任何已有客户端。 */
@Component
@EnableConfigurationProperties(BootstrapOAuthClientProperties.class)
final class InitialOAuthClientBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitialOAuthClientBootstrap.class);
    private static final long BOOTSTRAP_LOCK_ID = 6_264_374_646_334_052_354L;
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,127}");
    private static final Set<String> SCOPES = Set.of("openid", "profile");

    private final AuthorizationPersistenceConfiguration.AuthorizationJdbcOperations operations;
    private final RegisteredClientRepository repository;
    private final BootstrapOAuthClientProperties properties;

    InitialOAuthClientBootstrap(
            AuthorizationPersistenceConfiguration.AuthorizationJdbcOperations operations,
            RegisteredClientRepository repository,
            BootstrapOAuthClientProperties properties) {
        this.operations = operations;
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) {
            return;
        }
        ClientConfiguration configuration = validate(properties);
        Boolean created = new TransactionTemplate(operations.transactionManager())
                .execute(status -> initialize(configuration));
        if (Boolean.TRUE.equals(created)) {
            LOGGER.info("Initial OAuth client '{}' created", configuration.clientId());
        } else {
            LOGGER.info("Initial OAuth client '{}' already exists; bootstrap skipped", configuration.clientId());
        }
    }

    private boolean initialize(ClientConfiguration configuration) {
        operations.jdbcTemplate().execute("SELECT pg_advisory_xact_lock(" + BOOTSTRAP_LOCK_ID + ")");
        RegisteredClient existing = repository.findByClientId(configuration.clientId());
        Long iamCount = operations.jdbcTemplate().queryForObject("""
                SELECT count(*)
                  FROM iam_oauth_client
                 WHERE client_id = ?
                """, Long.class, configuration.clientId());
        if (existing != null || iamCount != null && iamCount > 0) {
            if (existing != null && iamCount != null && iamCount == 1
                    && matches(existing, configuration) && iamMatches(existing, configuration)) {
                return false;
            }
            throw new IllegalStateException(
                    "OAuth client bootstrap refused because existing protocol and IAM records are inconsistent");
        }

        UUID platformId = findPlatform(configuration.platformCode());
        String registeredClientId = UUID.randomUUID().toString();
        RegisteredClient client = RegisteredClient.withId(registeredClientId)
                .clientId(configuration.clientId())
                .clientName(configuration.clientName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(configuration.redirectUri())
                .postLogoutRedirectUri(configuration.postLogoutRedirectUri())
                .scopes(scopes -> scopes.addAll(SCOPES))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(configuration.requireConsent())
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        repository.save(client);

        UUID iamClientId = UUID.randomUUID();
        operations.jdbcTemplate().update("""
                INSERT INTO iam_oauth_client (
                    id, platform_id, client_id, client_type, grant_types, scopes,
                    access_token_seconds, refresh_token_seconds, pkce_required,
                    status, registered_client_id
                ) VALUES (?, ?, ?, 'PUBLIC', 'authorization_code,refresh_token',
                          'openid,profile', 900, 604800, TRUE, 'ENABLED', ?)
                """, iamClientId, platformId, configuration.clientId(), registeredClientId);
        operations.jdbcTemplate().update("""
                INSERT INTO iam_oauth_redirect_uri (id, oauth_client_id, redirect_uri)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), iamClientId, configuration.redirectUri());
        return true;
    }

    private boolean iamMatches(RegisteredClient client, ClientConfiguration configuration) {
        Boolean matches = operations.jdbcTemplate().queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM iam_oauth_client c
                      JOIN iam_platform p ON p.id = c.platform_id
                      JOIN iam_oauth_redirect_uri r ON r.oauth_client_id = c.id
                     WHERE c.client_id = ?
                       AND c.registered_client_id = ?
                       AND c.client_type = 'PUBLIC'
                       AND c.client_secret_hash IS NULL
                       AND c.grant_types = 'authorization_code,refresh_token'
                       AND c.scopes = 'openid,profile'
                       AND c.access_token_seconds = 900
                       AND c.refresh_token_seconds = 604800
                       AND c.pkce_required
                       AND c.status = 'ENABLED'
                       AND p.platform_code = ?
                       AND r.redirect_uri = ?
                       AND (SELECT count(*) FROM iam_oauth_redirect_uri all_r
                             WHERE all_r.oauth_client_id = c.id) = 1
                )
                """, Boolean.class, configuration.clientId(), client.getId(),
                configuration.platformCode(), configuration.redirectUri());
        return Boolean.TRUE.equals(matches);
    }

    private UUID findPlatform(String platformCode) {
        var platformIds = operations.jdbcTemplate().queryForList("""
                SELECT id
                  FROM iam_platform
                 WHERE platform_code = ?
                   AND status = 'ENABLED'
                   AND deleted_at IS NULL
                """, UUID.class, platformCode);
        if (platformIds.size() != 1) {
            throw new IllegalStateException("Exactly one enabled OAuth client platform is required");
        }
        return platformIds.getFirst();
    }

    private static boolean matches(RegisteredClient client, ClientConfiguration configuration) {
        return client.getClientName().equals(configuration.clientName())
                && client.getClientAuthenticationMethods().equals(Set.of(ClientAuthenticationMethod.NONE))
                && client.getAuthorizationGrantTypes().equals(Set.of(
                        AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN))
                && client.getRedirectUris().equals(Set.of(configuration.redirectUri()))
                && client.getPostLogoutRedirectUris().equals(Set.of(configuration.postLogoutRedirectUri()))
                && client.getScopes().equals(SCOPES)
                && client.getClientSettings().isRequireProofKey()
                && client.getClientSettings().isRequireAuthorizationConsent() == configuration.requireConsent()
                && client.getTokenSettings().getAccessTokenTimeToLive().equals(Duration.ofMinutes(15))
                && client.getTokenSettings().getRefreshTokenTimeToLive().equals(Duration.ofDays(7))
                && !client.getTokenSettings().isReuseRefreshTokens();
    }

    private static ClientConfiguration validate(BootstrapOAuthClientProperties properties) {
        String clientId = stripped(properties.clientId(), "client-id");
        if (!CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new IllegalStateException("Bootstrap OAuth client-id must be 3-128 safe ASCII characters");
        }
        String clientName = stripped(properties.clientName(), "client-name");
        String platformCode = stripped(properties.platformCode(), "platform-code");
        String redirectUri = validateRedirectUri(properties.redirectUri(), "redirect-uri");
        String postLogoutRedirectUri = validateRedirectUri(
                properties.postLogoutRedirectUri(), "post-logout-redirect-uri");
        return new ClientConfiguration(
                clientId, clientName, platformCode, redirectUri, postLogoutRedirectUri,
                properties.requireConsent());
    }

    private static String stripped(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing ocean.bootstrap.oauth-client." + property);
        }
        return value.strip();
    }

    private static String validateRedirectUri(String value, String property) {
        String candidate = stripped(value, property);
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalStateException("Invalid ocean.bootstrap.oauth-client." + property, invalidUri);
        }
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!uri.isAbsolute() || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || loopbackHttp)) {
            throw new IllegalStateException(
                    "OAuth redirect URI must use HTTPS, except HTTP loopback addresses are allowed for development");
        }
        return uri.toASCIIString();
    }

    private record ClientConfiguration(
            String clientId,
            String clientName,
            String platformCode,
            String redirectUri,
            String postLogoutRedirectUri,
            boolean requireConsent) {
    }
}
