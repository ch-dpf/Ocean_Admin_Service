package org.ocean.admin.auth;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.ocean.admin.auth.UserAuthenticationController.LoginRequest;
import org.ocean.admin.auth.UserAuthenticationController.LoginResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 第一方密码登录适配器；令牌仍由 SAS 生成并由官方 JDBC 授权服务保存。 */
@Service
public class PasswordLoginTokenService {

    private static final AuthorizationGrantType PASSWORD_LOGIN =
            new AuthorizationGrantType("urn:ocean:params:oauth:grant-type:password-login");

    private final AuthenticationManager authenticationManager;
    private final RegisteredClientRepository clientRepository;
    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<OAuth2Token> tokenGenerator;
    private final AuthorizationServerSettings authorizationServerSettings;
    private final JdbcTemplate jdbcTemplate;
    private final String firstPartyClientId;

    public PasswordLoginTokenService(
            AuthenticationManager authenticationManager,
            RegisteredClientRepository clientRepository,
            OAuth2AuthorizationService authorizationService,
            OAuth2TokenGenerator<OAuth2Token> tokenGenerator,
            AuthorizationServerSettings authorizationServerSettings,
            JdbcTemplate jdbcTemplate,
            @Value("${ocean.security.first-party-client-id:ocean-admin-web}") String firstPartyClientId) {
        this.authenticationManager = authenticationManager;
        this.clientRepository = clientRepository;
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
        this.authorizationServerSettings = authorizationServerSettings;
        this.jdbcTemplate = jdbcTemplate;
        this.firstPartyClientId = firstPartyClientId;
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        Authentication principal;
        try {
            principal = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username().strip(), request.password()));
        } catch (AuthenticationException failure) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        RegisteredClient client = requiredClient();
        Platform platform = requiredPlatform(client.getId());
        if (request.platformCode() != null
                && !request.platformCode().isBlank()
                && !platform.code().equalsIgnoreCase(request.platformCode().strip())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前登录客户端不属于请求的平台");
        }

        Set<String> scopes = client.getScopes().contains("profile")
                ? Set.of("profile") : Set.copyOf(client.getScopes());
        String authorizationId = UUID.randomUUID().toString();
        OAuth2Authorization baseAuthorization = OAuth2Authorization.withRegisteredClient(client)
                .id(authorizationId)
                .principalName(principal.getName())
                .authorizationGrantType(PASSWORD_LOGIN)
                .authorizedScopes(scopes)
                .attribute(Principal.class.getName(), principal)
                .build();

        DefaultOAuth2TokenContext.Builder context = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(principal)
                .authorizationServerContext(authorizationServerContext())
                .authorization(baseAuthorization)
                .authorizedScopes(scopes)
                .authorizationGrantType(PASSWORD_LOGIN)
                .authorizationGrant(principal);

        OAuth2Token generatedAccessToken = requiredGeneratedToken(
                context.tokenType(OAuth2TokenType.ACCESS_TOKEN).build(), "access token");
        if (!(generatedAccessToken instanceof Jwt jwt)) {
            throw serverError("SAS did not generate a self-contained JWT access token");
        }
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                TokenType.BEARER, jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), scopes);

        OAuth2Authorization.Builder authorization = OAuth2Authorization.from(baseAuthorization)
                .token(accessToken, metadata -> metadata.put(
                        OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                        mutableClaims(jwt.getClaims())));
        OAuth2Token generatedRefreshToken = requiredGeneratedToken(
                context.authorization(authorization.build())
                        .tokenType(OAuth2TokenType.REFRESH_TOKEN).build(), "refresh token");
        if (!(generatedRefreshToken instanceof OAuth2RefreshToken refreshToken)) {
            throw serverError("SAS did not generate a refresh token");
        }
        authorization.refreshToken(refreshToken);
        authorizationService.save(authorization.build());

        jdbcTemplate.update("""
                UPDATE ocean_platform.iam_user
                   SET last_login_at = ?, last_login_ip = ?, updated_at = now(), version = version + 1
                 WHERE username_normalized = lower(?) AND deleted_at IS NULL
                """, OffsetDateTime.now(), normalizedIp(clientIp), principal.getName());

        return response(jwt, refreshToken, platform.code());
    }

    private RegisteredClient requiredClient() {
        RegisteredClient client = clientRepository.findByClientId(firstPartyClientId);
        if (client == null
                || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            throw serverError("First-party OAuth client is missing or does not allow refresh tokens");
        }
        return client;
    }

    private Platform requiredPlatform(String registeredClientId) {
        List<Platform> platforms = jdbcTemplate.query("""
                SELECT p.id, p.platform_code
                  FROM ocean_platform.iam_oauth_client c
                  JOIN ocean_platform.iam_platform p ON p.id = c.platform_id
                 WHERE c.registered_client_id = ? AND c.status = 'ENABLED'
                   AND p.status = 'ENABLED' AND p.deleted_at IS NULL
                """, (resultSet, rowNumber) -> new Platform(
                resultSet.getObject("id", UUID.class), resultSet.getString("platform_code")),
                registeredClientId);
        if (platforms.size() != 1) {
            throw serverError("First-party OAuth client platform binding is invalid");
        }
        return platforms.getFirst();
    }

    private OAuth2Token requiredGeneratedToken(OAuth2TokenContext context, String name) {
        OAuth2Token token = tokenGenerator.generate(context);
        if (token == null) {
            throw serverError("SAS failed to generate " + name);
        }
        return token;
    }

    private AuthorizationServerContext authorizationServerContext() {
        return new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return authorizationServerSettings.getIssuer();
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return authorizationServerSettings;
            }
        };
    }

    private LoginResponse response(Jwt jwt, OAuth2RefreshToken refreshToken, String platformCode) {
        Instant now = Instant.now();
        long expiresIn = Math.max(0, Duration.between(now, jwt.getExpiresAt()).toSeconds());
        return new LoginResponse(
                jwt.getTokenValue(), jwt.getTokenValue(), refreshToken.getTokenValue(),
                "Bearer", expiresIn,
                uuidClaim(jwt, "user_id"), jwt.getSubject(), platformCode,
                uuidClaim(jwt, "sid"), strings(jwt, "roles"), strings(jwt, "permissions"));
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        if (value == null) {
            throw serverError("Generated access token is missing claim: " + name);
        }
        return UUID.fromString(value);
    }

    private static List<String> strings(Jwt jwt, String claim) {
        List<String> values = jwt.getClaimAsStringList(claim);
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, Object> mutableClaims(Map<String, Object> claims) {
        Map<String, Object> copy = new LinkedHashMap<>();
        claims.forEach((key, value) -> copy.put(key,
                value instanceof Set<?> set ? new ArrayList<>(set) : value));
        return copy;
    }

    private static String normalizedIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        String first = clientIp.split(",", 2)[0].strip();
        return first.length() <= 64 ? first : first.substring(0, 64);
    }

    private static ResponseStatusException serverError(String reason) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, reason);
    }

    private record Platform(UUID id, String code) { }
}
