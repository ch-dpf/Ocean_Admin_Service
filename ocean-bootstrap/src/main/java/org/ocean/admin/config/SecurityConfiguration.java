package org.ocean.admin.config;

import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
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
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthorizationServerProperties.class)
public class SecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }

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

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthorizationServerProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(required(properties.issuer(), "issuer").toString())
                .build();
    }

    @Bean
    RSAKey authorizationServerRsaKey(AuthorizationServerProperties properties) {
        try {
            char[] password = required(properties.keyStorePassword(), "key-store-password").toCharArray();
            KeyStore keyStore = KeyStore.getInstance(required(properties.keyStoreType(), "key-store-type"));
            try (InputStream input = required(properties.keyStore(), "key-store").getInputStream()) {
                keyStore.load(input, password);
            }

            String alias = required(properties.keyAlias(), "key-alias");
            Key key = keyStore.getKey(alias, password);
            Certificate certificate = keyStore.getCertificate(alias);
            if (!(key instanceof RSAPrivateKey privateKey)
                    || certificate == null
                    || !(certificate.getPublicKey() instanceof RSAPublicKey publicKey)) {
                throw new IllegalStateException("OAuth2 signing key must be an RSA private key with a certificate");
            }

            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(required(properties.keyId(), "key-id"))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load OAuth2 signing key", exception);
        }
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey authorizationServerRsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(authorizationServerRsaKey));
    }

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

    private static <T> T required(T value, String property) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalStateException(
                    "Missing ocean.security.authorization-server." + property + " configuration");
        }
        return value;
    }
}
