package org.ocean.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ocean.admin.platform.identity.session.SessionState;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisSessionStateCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");
    private static final UUID SESSION_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID FAMILY_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private RedisSessionStateCache cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        SessionCacheProperties properties = new SessionCacheProperties();
        cache = new RedisSessionStateCache(
                redisTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void capsCacheEntryAtConfiguredMaximumTtl() {
        cache.put(activeState());

        verify(values).set(
                eq("ocean:iam:session:" + SESSION_ID),
                anyString(),
                eq(Duration.ofMinutes(5)));
    }

    @Test
    void treatsReadFailureAsCacheMiss() {
        when(values.get(anyString())).thenThrow(
                new DataAccessResourceFailureException("redis unavailable"));

        assertThat(cache.find(SESSION_ID)).isEmpty();
    }

    @Test
    void doesNotPropagateWriteOrEvictionFailures() {
        when(values.setIfAbsent(anyString(), anyString())).thenThrow(
                new DataAccessResourceFailureException("redis unavailable"));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis unavailable"))
                .when(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        when(redisTemplate.delete(anyString())).thenThrow(
                new DataAccessResourceFailureException("redis unavailable"));

        assertThatCode(() -> cache.put(activeState())).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict(SESSION_ID)).doesNotThrowAnyException();
    }

    private static SessionState activeState() {
        return new SessionState(
                SESSION_ID, FAMILY_ID, 3,
                NOW.plus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(7)));
    }
}
