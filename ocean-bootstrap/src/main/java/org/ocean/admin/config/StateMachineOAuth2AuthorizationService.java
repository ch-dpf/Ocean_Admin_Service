package org.ocean.admin.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.ocean.admin.platform.identity.session.RefreshTokenLifecycleService;
import org.ocean.admin.platform.identity.session.RefreshTokenPresentation;
import org.ocean.admin.platform.identity.session.RefreshTokenPresentationStatus;
import org.ocean.admin.platform.identity.session.RefreshTokenRotation;
import org.ocean.admin.platform.identity.session.RefreshTokenRotationStatus;
import org.ocean.admin.platform.identity.session.SessionIssueCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 用 IAM 数据库状态机装饰 SAS 官方 JDBC 授权服务。
 * 所有协议授权写入与会话状态转换共用一个 PostgreSQL 事务。
 */
final class StateMachineOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private static final OAuth2TokenType REFRESH_TOKEN = OAuth2TokenType.REFRESH_TOKEN;
    private static final String REVOCATION_REASON = "OAUTH2_TOKEN_REVOCATION";

    private final OAuth2AuthorizationService delegate;
    private final RefreshTokenLifecycleService lifecycleService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SessionLifecycleProperties properties;
    private final Clock clock;

    StateMachineOAuth2AuthorizationService(
            OAuth2AuthorizationService delegate,
            RefreshTokenLifecycleService lifecycleService,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            SessionLifecycleProperties properties) {
        this(delegate, lifecycleService, jdbcTemplate, transactionManager, properties, Clock.systemUTC());
    }

    StateMachineOAuth2AuthorizationService(
            OAuth2AuthorizationService delegate,
            RefreshTokenLifecycleService lifecycleService,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            SessionLifecycleProperties properties,
            Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        SaveOutcome outcome = transactionTemplate.execute(status -> saveInTransaction(authorization));
        if (outcome != null && outcome.errorCode() != null) {
            throw oauthError(outcome.errorCode(), outcome.description());
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        transactionTemplate.executeWithoutResult(status -> {
            OAuth2Authorization existing = delegate.findById(authorization.getId());
            revokeExistingRefreshToken(existing, "OAUTH2_AUTHORIZATION_REMOVED");
            delegate.remove(authorization);
        });
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (token == null || token.isBlank()) {
            return null;
        }
        RefreshTokenPresentation presentation = null;
        if (tokenType == null || REFRESH_TOKEN.equals(tokenType)) {
            presentation = lifecycleService.validatePresentation(token);
            if (presentation.status() != RefreshTokenPresentationStatus.ACTIVE
                    && presentation.status() != RefreshTokenPresentationStatus.INVALID) {
                return null;
            }
        }

        OAuth2Authorization authorization = delegate.findByToken(token, tokenType);
        if (presentation != null
                && presentation.status() == RefreshTokenPresentationStatus.INVALID
                && (REFRESH_TOKEN.equals(tokenType) || isRefreshToken(authorization, token))) {
            return null;
        }
        return authorization;
    }

    private SaveOutcome saveInTransaction(OAuth2Authorization authorization) {
        authorization = normalizeTokenMetadata(authorization);
        OAuth2Authorization existing = delegate.findById(authorization.getId());
        OAuth2Authorization.Token<OAuth2RefreshToken> previous = refreshToken(existing);
        OAuth2Authorization.Token<OAuth2RefreshToken> current = refreshToken(authorization);

        if (previous == null && current != null) {
            issueSession(authorization, current.getToken());
        } else if (previous != null && current != null
                && !previous.getToken().getTokenValue().equals(current.getToken().getTokenValue())) {
            RefreshTokenRotation rotation = lifecycleService.rotate(
                    previous.getToken().getTokenValue(),
                    current.getToken().getTokenValue(),
                    requiredExpiry(current.getToken()));
            if (rotation.status() != RefreshTokenRotationStatus.ROTATED) {
                return new SaveOutcome(OAuth2ErrorCodes.INVALID_GRANT,
                        "Refresh token was rejected by the authoritative session state");
            }
        } else if (previous != null && shouldRevoke(existing, authorization)) {
            lifecycleService.revokeByRefreshToken(
                    previous.getToken().getTokenValue(), REVOCATION_REASON);
        }

        delegate.save(authorization);
        return SaveOutcome.saved();
    }

    /**
     * Jackson 3 的安全类型校验不接受 JDK 的 ImmutableCollections 内部实现。
     * 在 JDBC 序列化前只规范化 JWT metadata 中的容器类型，不放宽反序列化白名单。
     */
    private static OAuth2Authorization normalizeTokenMetadata(OAuth2Authorization authorization) {
        OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken != null) {
            builder.token(accessToken.getToken(), metadata -> replaceNormalized(metadata, accessToken.getMetadata()));
        }
        OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        if (idToken != null) {
            builder.token(idToken.getToken(), metadata -> replaceNormalized(metadata, idToken.getMetadata()));
        }
        return builder.build();
    }

    private static void replaceNormalized(Map<String, Object> target, Map<String, Object> source) {
        target.clear();
        source.forEach((key, value) -> target.put(key, normalizeValue(value)));
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nested) -> normalized.put(key, normalizeValue(nested)));
            return normalized;
        }
        if (value instanceof java.util.Set<?> set) {
            LinkedHashSet<Object> normalized = new LinkedHashSet<>();
            set.forEach(item -> normalized.add(normalizeValue(item)));
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>();
            collection.forEach(item -> normalized.add(normalizeValue(item)));
            return normalized;
        }
        return value;
    }

    private void issueSession(OAuth2Authorization authorization, OAuth2RefreshToken refreshToken) {
        SessionOwner owner = resolveOwner(authorization);
        Instant now = clock.instant();
        Instant sessionExpiresAt = now.plus(properties.maximumLifetime());
        Instant refreshExpiresAt = requiredExpiry(refreshToken);
        if (refreshExpiresAt.isAfter(sessionExpiresAt)) {
            throw oauthError(OAuth2ErrorCodes.SERVER_ERROR,
                    "Refresh token lifetime exceeds the configured session maximum lifetime");
        }
        HttpServletRequest request = currentRequest();
        lifecycleService.issue(new SessionIssueCommand(
                owner.userId(), owner.platformId(), owner.oauthClientId(),
                attributeOrHeader(request, "ocean.login.device-id", "X-Device-Id"),
                attributeOrHeader(request, "ocean.login.device-name", "X-Device-Name"),
                request == null ? null : request.getRemoteAddr(), header(request, "User-Agent"),
                sessionExpiresAt, refreshExpiresAt), refreshToken.getTokenValue(),
                authorizationSessionId(authorization.getId()));
    }

    private SessionOwner resolveOwner(OAuth2Authorization authorization) {
        List<SessionOwner> owners = jdbcTemplate.query("""
                SELECT u.id AS user_id, c.platform_id, c.id AS oauth_client_id
                  FROM ocean_platform.iam_user u
                 JOIN ocean_platform.iam_oauth_client c ON c.registered_client_id = ?
                 WHERE u.username_normalized = ? AND u.deleted_at IS NULL
                   AND c.status = 'ENABLED'
                """, (resultSet, rowNumber) -> new SessionOwner(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("platform_id", UUID.class),
                resultSet.getObject("oauth_client_id", UUID.class)),
                authorization.getRegisteredClientId(),
                authorization.getPrincipalName().strip().toLowerCase(Locale.ROOT));
        if (owners.size() != 1) {
            throw oauthError(OAuth2ErrorCodes.SERVER_ERROR,
                    "Unable to resolve IAM user and OAuth client for the authorization");
        }
        return owners.getFirst();
    }

    private void revokeExistingRefreshToken(OAuth2Authorization authorization, String reason) {
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = refreshToken(authorization);
        if (refreshToken != null) {
            lifecycleService.revokeByRefreshToken(refreshToken.getToken().getTokenValue(), reason);
        }
    }

    private static boolean shouldRevoke(
            OAuth2Authorization previous, OAuth2Authorization current) {
        OAuth2Authorization.Token<OAuth2RefreshToken> currentRefresh = refreshToken(current);
        if (currentRefresh != null && currentRefresh.isInvalidated()) {
            return true;
        }
        return previous.getAccessToken() != null
                && previous.getAccessToken().isActive()
                && current.getAccessToken() != null
                && current.getAccessToken().isInvalidated();
    }

    private static boolean isRefreshToken(OAuth2Authorization authorization, String token) {
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = refreshToken(authorization);
        return refreshToken != null && refreshToken.getToken().getTokenValue().equals(token);
    }

    private static OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken(
            OAuth2Authorization authorization) {
        return authorization == null ? null : authorization.getRefreshToken();
    }

    private static Instant requiredExpiry(OAuth2RefreshToken refreshToken) {
        if (refreshToken.getExpiresAt() == null) {
            throw oauthError(OAuth2ErrorCodes.SERVER_ERROR, "Refresh token expiry is required");
        }
        return refreshToken.getExpiresAt();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private static String attributeOrHeader(
            HttpServletRequest request, String attributeName, String headerName) {
        if (request == null) {
            return null;
        }
        Object attribute = request.getAttribute(attributeName);
        return attribute instanceof String text && !text.isBlank()
                ? text : request.getHeader(headerName);
    }

    private static OAuth2AuthenticationException oauthError(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }

    static UUID authorizationSessionId(String authorizationId) {
        try {
            return UUID.fromString(authorizationId);
        } catch (IllegalArgumentException invalidId) {
            throw oauthError(OAuth2ErrorCodes.SERVER_ERROR,
                    "SAS authorization id must be a UUID for managed session issuance");
        }
    }

    private record SessionOwner(UUID userId, UUID platformId, UUID oauthClientId) {
    }

    private record SaveOutcome(String errorCode, String description) {
        private static SaveOutcome saved() {
            return new SaveOutcome(null, null);
        }
    }
}
