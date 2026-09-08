package org.ocean.admin.config;

import java.security.Principal;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在官方授权码 Provider 完成全部校验和 access/id token 生成后，为强制 PKCE 的公共客户端
 * 补发可轮换 refresh token。整个官方保存与 IAM 会话签发处于同一数据库事务。
 */
final class PublicClientRefreshTokenAuthenticationProvider implements AuthenticationProvider {

    private static final OAuth2TokenType AUTHORIZATION_CODE =
            new OAuth2TokenType(OAuth2ParameterNames.CODE);

    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizationService;
    private final StringKeyGenerator refreshTokenGenerator = new Base64StringKeyGenerator(
            Base64.getUrlEncoder().withoutPadding(), 96);
    private final TransactionTemplate transactionTemplate;

    PublicClientRefreshTokenAuthenticationProvider(
            AuthenticationProvider delegate,
            OAuth2AuthorizationService authorizationService,
            PlatformTransactionManager transactionManager) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result = transactionTemplate.execute(status -> authenticateInTransaction(authentication));
        return Objects.requireNonNull(result, "authorization code authentication result");
    }

    private Authentication authenticateInTransaction(Authentication authentication) {
        Authentication delegated = delegate.authenticate(authentication);
        if (!(authentication instanceof OAuth2AuthorizationCodeAuthenticationToken grant)
                || !(delegated instanceof OAuth2AccessTokenAuthenticationToken tokens)
                || tokens.getRefreshToken() != null
                || !isEligiblePublicClient(tokens)) {
            return delegated;
        }

        OAuth2Authorization authorization = authorizationService.findByToken(
                grant.getCode(), AUTHORIZATION_CODE);
        if (authorization == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.SERVER_ERROR,
                    "Authorization disappeared before public-client refresh token issuance", null));
        }
        Authentication userPrincipal = authorization.getAttribute(Principal.class.getName());
        if (userPrincipal == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.SERVER_ERROR);
        }

        Instant issuedAt = Instant.now();
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                refreshTokenGenerator.generateKey(), issuedAt,
                issuedAt.plus(tokens.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive()));

        authorizationService.save(OAuth2Authorization.from(authorization)
                .refreshToken(refreshToken)
                .build());
        return new OAuth2AccessTokenAuthenticationToken(
                tokens.getRegisteredClient(), (Authentication) tokens.getPrincipal(),
                tokens.getAccessToken(), refreshToken, tokens.getAdditionalParameters());
    }

    private static boolean isEligiblePublicClient(OAuth2AccessTokenAuthenticationToken tokens) {
        return tokens.getRegisteredClient().getClientAuthenticationMethods()
                .contains(ClientAuthenticationMethod.NONE)
                && tokens.getRegisteredClient().getAuthorizationGrantTypes()
                .contains(AuthorizationGrantType.REFRESH_TOKEN)
                && tokens.getRegisteredClient().getClientSettings().isRequireProofKey();
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
