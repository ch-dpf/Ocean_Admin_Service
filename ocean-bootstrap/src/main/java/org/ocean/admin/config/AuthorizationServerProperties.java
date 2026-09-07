package org.ocean.admin.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties("ocean.security.authorization-server")
public record AuthorizationServerProperties(
        URI issuer,
        String audience,
        String keyId,
        Resource keyStore,
        String keyStoreType,
        String keyAlias,
        String keyStorePassword) {
}
