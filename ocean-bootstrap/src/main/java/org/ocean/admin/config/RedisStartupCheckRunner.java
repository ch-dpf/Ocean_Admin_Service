package org.ocean.admin.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 服务启动后立即执行 Redis 连通性检查，避免运行中才暴露配置问题。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RedisStartupCheckRunner implements ApplicationRunner {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.redis.startup-check.enabled:true}")
    private boolean enabled;

    @Value("${app.redis.startup-check.strict:true}")
    private boolean strict;

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.database:0}")
    private int redisDb;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Redis 启动检查已关闭");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("Redis PING 返回异常: " + pong);
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("Redis 启动检查通过: {}:{} db={} ping={} elapsed={}ms", redisHost, redisPort, redisDb, pong, elapsed);
        } catch (Exception ex) {
            String message = String.format("Redis 启动检查失败: %s:%d db=%d, error=%s", redisHost, redisPort, redisDb, ex.getMessage());
            if (strict) {
                throw new IllegalStateException(message, ex);
            }
            log.error(message, ex);
        }
    }
}

