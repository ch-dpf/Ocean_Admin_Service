package org.ocean.admin.config;

import java.time.Clock;

import org.ocean.admin.platform.identity.session.SessionStateCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 将 Redis 作为可关闭、可丢弃的活跃会话缓存装配到数据库状态机。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionCacheProperties.class)
class SessionCacheConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "ocean.security.session-cache",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SessionStateCache redisSessionStateCache(
            StringRedisTemplate redisTemplate,
            SessionCacheProperties properties) {
        return new RedisSessionStateCache(redisTemplate, properties, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(SessionStateCache.class)
    SessionStateCache noOpSessionStateCache() {
        return SessionStateCache.noOp();
    }
}
