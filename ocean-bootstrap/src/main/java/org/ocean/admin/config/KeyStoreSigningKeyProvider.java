package org.ocean.admin.config;

import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 测试环境从固定 KeyStore 加载可重复使用的 RSA 签名密钥。 */
@Component
@Profile("test")
final class KeyStoreSigningKeyProvider implements SigningKeyProvider {

    private final RSAKey signingKey;

    KeyStoreSigningKeyProvider(AuthorizationServerProperties properties) {
        this.signingKey = load(properties);
    }

    @Override
    public RSAKey signingKey() {
        return signingKey;
    }

    private static RSAKey load(AuthorizationServerProperties properties) {
        char[] password = required(properties.keyStorePassword(), "key-store-password").toCharArray();
        try {
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
            return JwkKeyUtils.rsaKey(publicKey, privateKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load OAuth2 signing key", exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static <T> T required(T value, String property) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalStateException(
                    "Missing ocean.security.authorization-server." + property + " configuration");
        }
        return value;
    }
}
