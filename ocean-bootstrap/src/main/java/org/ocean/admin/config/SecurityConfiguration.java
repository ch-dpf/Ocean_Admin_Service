package org.ocean.admin.config;

import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.ocean.admin.platform.identity.session.RefreshTokenLifecycleService;

/**
 * OAuth2 授权服务器与业务资源服务器的安全配置。
 * 两条过滤器链通过顺序隔离：授权协议端点优先匹配，其余请求再进入应用安全链。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({AuthorizationServerProperties.class, SessionLifecycleProperties.class})
public class SecurityConfiguration {

    /** 保护授权、令牌、JWK 和 OIDC 等协议端点，并为交互式授权启用表单登录。 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            OAuth2AuthorizationService authorizationService,
            RegisteredClientRepository registeredClientRepository,
            PlatformTransactionManager transactionManager) throws Exception {
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.clientAuthentication(clientAuthentication -> clientAuthentication
                    .authenticationConverters(converters -> converters.add(
                            0, new PublicRefreshClientAuthenticationConverter()))
                    .authenticationProviders(providers -> providers.add(
                            0, new PublicRefreshClientAuthenticationProvider(registeredClientRepository))));
            authorizationServer.tokenEndpoint(tokenEndpoint ->
                    tokenEndpoint.authenticationProviders(providers -> {
                        for (int index = 0; index < providers.size(); index++) {
                            if (providers.get(index) instanceof OAuth2AuthorizationCodeAuthenticationProvider) {
                                providers.set(index, new PublicClientRefreshTokenAuthenticationProvider(
                                        providers.get(index), authorizationService, transactionManager));
                                break;
                            }
                        }
                    }));
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }

    /** 管理 API 只接受 Bearer JWT，并且不创建或读取浏览器会话。 */
    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {
        http.securityMatcher("/api/**");
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.requestCache(AbstractHttpConfigurer::disable);
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                .accessDeniedHandler(new BearerTokenAccessDeniedHandler()));
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                        "/api/user/login",
                        "/api/user/platform-login")
                .permitAll()
                .anyRequest().authenticated());
        http.oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(new OceanJwtAuthenticationConverter())));
        return http.build();
    }

    /** 暴露与表单登录相同的认证管理器，供第一方 REST 登录入口复用。 */
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /** 承载登录页、文档和运维端点；健康信息允许匿名读取。 */
    @Bean
    @Order(3)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/error", "/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated());
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }

    /** 使用显式配置的 issuer，确保发现文档与令牌签发者保持一致。 */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthorizationServerProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(required(properties.issuer(), "issuer").toString())
                .build();
    }

    /** 从当前运行环境对应的 Provider 获取 JWT 签名密钥。 */
    @Bean
    RSAKey authorizationServerRsaKey(SigningKeyProvider signingKeyProvider) {
        return signingKeyProvider.signingKey();
    }

    /** 统一生成带算法标识的密码哈希，当前默认编码器为 bcrypt。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 将签名密钥发布为授权服务器可使用的 JWK 数据源。 */
    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey authorizationServerRsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(authorizationServerRsaKey));
    }

    /**
     * 创建资源服务器的 JWT 解码器，并同时校验 issuer 与本服务要求的 audience。
     * 仅验证签名而不验证受众，可能导致签发给其他服务的令牌被误接受。
     */
    @Bean
    JwtDecoder jwtDecoder(
            RSAKey authorizationServerRsaKey,
            AuthorizationServerProperties properties,
            RefreshTokenLifecycleService lifecycleService)
            throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(authorizationServerRsaKey.toRSAPublicKey())
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(
                required(properties.issuer(), "issuer").toString());
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            String audience = required(properties.audience(), "audience");
            if (jwt.getAudience().contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Required audience is missing", null));
        };
        OAuth2TokenValidator<Jwt> activeSessionValidator = jwt -> {
            String sessionId = jwt.getClaimAsString("sid");
            if (sessionId == null) {
                return OAuth2TokenValidatorResult.success();
            }
            try {
                if (lifecycleService.findActiveSession(java.util.UUID.fromString(sessionId)).isPresent()) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (IllegalArgumentException invalidSessionId) {
                // 统一映射为无效令牌，避免向调用方暴露内部标识解析细节。
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Authorization session is no longer active", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator, audienceValidator, activeSessionValidator));
        return decoder;
    }

    /** 对启动所需配置执行快速失败校验，避免带着不完整安全配置运行。 */
    private static <T> T required(T value, String property) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalStateException(
                    "Missing ocean.security.authorization-server." + property + " configuration");
        }
        return value;
    }
}
