package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 验证环境密钥 Provider 的生命周期和 JWK Thumbprint kid 策略。 */
class SigningKeyProviderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DevelopmentSigningKeyProvider.class,
                    KeyStoreSigningKeyProvider.class);

    @Test
    void devProfileSelectsOnlyDevelopmentProvider() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> {
                    assertThat(context).hasSingleBean(SigningKeyProvider.class);
                    assertThat(context.getBean(SigningKeyProvider.class))
                            .isInstanceOf(DevelopmentSigningKeyProvider.class);
                });
    }

    @Test
    void prodProfileNeverFallsBackToProcessLocalKey() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).doesNotHaveBean(SigningKeyProvider.class));
    }

    @Test
    void developmentProviderGeneratesOneProcessLocalKeyWithThumbprintKid() throws Exception {
        DevelopmentSigningKeyProvider provider = new DevelopmentSigningKeyProvider();

        RSAKey first = provider.signingKey();
        RSAKey second = provider.signingKey();

        assertThat(second).isSameAs(first);
        assertThat(first.isPrivate()).isTrue();
        assertThat(first.size()).isGreaterThanOrEqualTo(DevelopmentSigningKeyProvider.RSA_KEY_SIZE);
        assertThat(first.getKeyID())
                .isEqualTo(first.toPublicJWK().computeThumbprint().toString());
    }

    @Test
    void thumbprintKidIsDerivedOnlyFromPublicKey() throws Exception {
        KeyPair keyPair = KeyGeneratorUtils.generateRsaKey(2048);

        RSAKey key = JwkKeyUtils.rsaKey(
                (RSAPublicKey) keyPair.getPublic(),
                (RSAPrivateKey) keyPair.getPrivate());

        assertThat(key.getKeyID()).isEqualTo(key.toPublicJWK().computeThumbprint().toString());
        assertThat(key.toPublicJWK().getKeyID()).isEqualTo(key.getKeyID());
    }
}
