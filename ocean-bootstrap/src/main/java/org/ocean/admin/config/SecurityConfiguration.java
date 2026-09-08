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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * OAuth2 授权服务器与业务资源服务器的安全配置。
 * 两条过滤器链通过顺序隔离：授权协议端点优先匹配，其余请求再进入应用安全链。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthorizationServerProperties.class)
public class SecurityConfiguration {

    /** 保护授权、令牌、JWK 和 OIDC 等协议端点，并为交互式授权启用表单登录。 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }

    /** 保护普通业务接口，同时允许健康检查匿名访问。 */
    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated());
        http.oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.decoder(jwtDecoder)));
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
    JwtDecoder jwtDecoder(RSAKey authorizationServerRsaKey, AuthorizationServerProperties properties)
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
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
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
