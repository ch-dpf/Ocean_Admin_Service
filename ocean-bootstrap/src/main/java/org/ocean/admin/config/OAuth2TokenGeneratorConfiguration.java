package org.ocean.admin.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/** 让授权服务器协议端点与第一方登录接口复用同一套令牌生成器。 */
@Configuration(proxyBeanMethods = false)
public class OAuth2TokenGeneratorConfiguration {

    /**
     * 显式暴露 SAS 默认组合生成器，供协议过滤器链和业务登录服务共同使用。
     * 手动提供生成器后必须同步装配 JWT 定制器，否则业务身份声明不会写入令牌。
     */
    @Bean
    OAuth2TokenGenerator<OAuth2Token> oauth2TokenGenerator(
            JWKSource<SecurityContext> jwkSource,
            AuthorizationTokenCustomizer tokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(tokenCustomizer);

        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator,
                new OAuth2AccessTokenGenerator(),
                new OAuth2RefreshTokenGenerator());
    }
}
