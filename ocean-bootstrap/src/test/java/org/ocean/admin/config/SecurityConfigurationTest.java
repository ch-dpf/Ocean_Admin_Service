package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** 验证资源服务器只接受签名、签发者和受众均符合配置的 JWT。 */
class SecurityConfigurationTest {

    private static final String ISSUER = "https://identity.example.test";
    private static final String AUDIENCE = "ocean-admin-api";
    private static RSAKey rsaKey;
    private static JwtDecoder decoder;

    /** 为所有用例创建一次临时 RSA 密钥，避免测试依赖部署密钥库。 */
    @BeforeAll
    static void createDecoder() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("test-key")
                .build();
        AuthorizationServerProperties properties = new AuthorizationServerProperties(
                java.net.URI.create(ISSUER), AUDIENCE, null,
                "PKCS12", "test", "test");
        decoder = new SecurityConfiguration().jwtDecoder(
                rsaKey, properties,
                org.mockito.Mockito.mock(
                        org.ocean.admin.platform.identity.session.RefreshTokenLifecycleService.class));
    }

    /** 正确 issuer 与 audience 的令牌应通过完整校验链。 */
    @Test
    void acceptsTokenWithConfiguredIssuerAndAudience() throws Exception {
        Jwt jwt = decoder.decode(signedToken(ISSUER, AUDIENCE));

        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
    }

    /** 防止本服务误接受签发给其他资源服务器的令牌。 */
    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        String token = signedToken(ISSUER, "another-api");

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Required audience is missing");
    }

    /** 防止受信密钥下由非预期签发者生成的令牌被接受。 */
    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        String token = signedToken("https://attacker.example.test", AUDIENCE);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    /** 使用测试私钥生成有效期一分钟的 RS256 令牌。 */
    private static String signedToken(String issuer, String audience) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("alice")
                .audience(audience)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
