package org.ocean.admin.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * OAuth2/OIDC 授权服务器配置。
 * 测试环境使用密钥库字段加载固定 RSA 密钥，开发环境使用进程级动态密钥。
 * 颁发者和受众用于令牌签发及校验。
 */
@ConfigurationProperties("ocean.security.authorization-server")
public record AuthorizationServerProperties(
        URI issuer,
        String audience,
        Resource keyStore,
        String keyStoreType,
        String keyAlias,
        String keyStorePassword) {
}
