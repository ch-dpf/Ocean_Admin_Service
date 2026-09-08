package org.ocean.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 首个浏览器 OAuth 客户端的受控初始化配置。 */
@ConfigurationProperties("ocean.bootstrap.oauth-client")
record BootstrapOAuthClientProperties(
        boolean enabled,
        String clientId,
        String clientName,
        String platformCode,
        String redirectUri,
        String postLogoutRedirectUri,
        boolean requireConsent) {
}
