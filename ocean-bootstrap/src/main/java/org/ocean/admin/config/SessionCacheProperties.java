package org.ocean.admin.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 活跃会话 Redis 投影的边界配置。 */
@ConfigurationProperties("ocean.security.session-cache")
public class SessionCacheProperties {

    private boolean enabled = true;
    private String keyPrefix = "ocean:iam:session:";
    private Duration maximumTtl = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getMaximumTtl() {
        return maximumTtl;
    }

    public void setMaximumTtl(Duration maximumTtl) {
        this.maximumTtl = maximumTtl;
    }
}
