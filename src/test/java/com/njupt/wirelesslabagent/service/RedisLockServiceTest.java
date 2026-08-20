package com.njupt.wirelesslabagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLockServiceTest {

    @Test
    void shouldAcquireWithSetIfAbsentAndTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Duration ttl = Duration.ofMinutes(5);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("lock:key"), anyString(), eq(ttl))).thenReturn(true);

        String token = new RedisLockService(redis).tryAcquire("lock:key", ttl);

        assertNotNull(token);
        verify(values).setIfAbsent("lock:key", token, ttl);
    }

    @Test
    void shouldReturnNullWhenLockIsAlreadyHeld() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("lock:key"), anyString(), any(Duration.class))).thenReturn(false);

        assertNull(new RedisLockService(redis).tryAcquire("lock:key", Duration.ofSeconds(10)));
    }

    @Test
    void shouldReleaseWithAtomicCompareAndDeleteScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of("lock:key")), eq("owner-token")))
                .thenReturn(1L);

        boolean released = new RedisLockService(redis).release("lock:key", "owner-token");

        assertTrue(released);
        verify(redis).execute(any(RedisScript.class), eq(List.of("lock:key")), eq("owner-token"));
    }

    @Test
    void shouldNotReleaseWhenTokenDoesNotOwnLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of("lock:key")), eq("stale-token")))
                .thenReturn(0L);

        assertFalse(new RedisLockService(redis).release("lock:key", "stale-token"));
    }
}
