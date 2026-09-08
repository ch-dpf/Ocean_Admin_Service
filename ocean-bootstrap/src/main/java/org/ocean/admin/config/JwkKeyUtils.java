package org.ocean.admin.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

/** 构造带 RFC 7638 JWK Thumbprint kid 的 RSA JWK。 */
final class JwkKeyUtils {

    private JwkKeyUtils() {
    }

    static RSAKey rsaKey(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        try {
            RSAKey publicJwk = new RSAKey.Builder(publicKey).build();
            String keyId = publicJwk.computeThumbprint().toString();
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .build();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to compute RSA JWK thumbprint", exception);
        }
    }
}
