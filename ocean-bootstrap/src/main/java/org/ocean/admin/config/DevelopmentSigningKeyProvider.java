package org.ocean.admin.config;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 开发环境使用的进程级临时密钥；应用重启后旧令牌自然失效。 */
@Component
@Profile("dev")
final class DevelopmentSigningKeyProvider implements SigningKeyProvider {

    static final int RSA_KEY_SIZE = 3072;

    private final RSAKey signingKey;

    DevelopmentSigningKeyProvider() {
        KeyPair keyPair = KeyGeneratorUtils.generateRsaKey(RSA_KEY_SIZE);
        this.signingKey = JwkKeyUtils.rsaKey(
                (RSAPublicKey) keyPair.getPublic(),
                (RSAPrivateKey) keyPair.getPrivate());
    }

    @Override
    public RSAKey signingKey() {
        return signingKey;
    }
}
