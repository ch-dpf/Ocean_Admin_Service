package org.ocean.admin.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** 保留 Boot 的认证连接配置，为进度和大载荷缓存分配独立连接及命令队列。 */
@Configuration(proxyBeanMethods = false)
public class RedisConnectionConfig {
    @Bean
    LettuceClientConfigurationBuilderCustomizer redisClientOptions(
            @Value("${spring.data.redis.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.redis.request-queue-size:128}") int queueSize) {
        if (connectTimeout.isNegative() || connectTimeout.isZero() || queueSize < 1)
            throw new IllegalArgumentException("Redis连接超时和队列容量必须为正数");
        return builder -> builder.clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).keepAlive(true).build())
                .timeoutOptions(TimeoutOptions.enabled())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(queueSize).build());
    }

    // 非默认候选不会使 Boot 的自动配置退让，认证仍使用原有 redisConnectionFactory。
    @Bean(defaultCandidate = false)
    LettuceConnectionFactory progressRedisConnectionFactory(
            @Qualifier("redisConnectionFactory") LettuceConnectionFactory primary,
            @Value("${app.redis.progress-timeout:1s}") Duration timeout) {
        return isolatedFactory(primary, "ocean-progress", timeout);
    }

    @Bean(defaultCandidate = false)
    LettuceConnectionFactory tileRedisConnectionFactory(
            @Qualifier("redisConnectionFactory") LettuceConnectionFactory primary,
            @Value("${app.redis.tile-timeout:500ms}") Duration timeout) {
        return isolatedFactory(primary, "ocean-tile-cache", timeout);
    }

    @Bean(defaultCandidate = false)
    StringRedisTemplate progressRedisTemplate(
            @Qualifier("progressRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean(defaultCandidate = false)
    StringRedisTemplate tileRedisTemplate(
            @Qualifier("tileRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private LettuceConnectionFactory isolatedFactory(LettuceConnectionFactory primary, String name, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Redis命令超时必须为正数");
        var source = primary.getClientConfiguration();
        var builder = LettuceClientConfiguration.builder().commandTimeout(timeout).clientName(name)
                .shutdownTimeout(source.getShutdownTimeout()).shutdownQuietPeriod(source.getShutdownQuietPeriod());
        source.getClientOptions().ifPresent(builder::clientOptions);
        source.getClientResources().ifPresent(builder::clientResources);
        source.getReadFrom().ifPresent(builder::readFrom);
        source.getRedisCredentialsProviderFactory().ifPresent(builder::redisCredentialsProviderFactory);
        if (source.isUseSsl()) {
            var ssl = builder.useSsl().verifyPeer(source.getVerifyMode());
            if (source.isStartTls()) ssl.startTls();
        }
        RedisConfiguration server = primary.getStandaloneConfiguration();
        if (primary.getSentinelConfiguration() != null) server = primary.getSentinelConfiguration();
        else if (primary.getClusterConfiguration() != null) server = primary.getClusterConfiguration();
        else if (primary.getSocketConfiguration() != null) server = primary.getSocketConfiguration();
        return new LettuceConnectionFactory(server, builder.build());
    }
}
