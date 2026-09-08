package org.ocean.admin.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OAuth 会话的绝对生命周期上限；刷新令牌自身仍使用客户端 TokenSettings 的 TTL。 */
@ConfigurationProperties("ocean.security.session")
public record SessionLifecycleProperties(Duration maximumLifetime) {

    public SessionLifecycleProperties {
        if (maximumLifetime == null || maximumLifetime.isNegative() || maximumLifetime.isZero()) {
            maximumLifetime = Duration.ofDays(30);
        }
    }
}
