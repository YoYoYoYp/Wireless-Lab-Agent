package com.njupt.wirelesslabagent.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 轻量 Redis 锁：SET NX 获取，Lua 比较 token 后删除，避免误删已过期并被他人重建的锁。
 */
@Service
public class RedisLockService {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @return 获取成功时返回唯一 token；锁已被占用时返回 null。
     */
    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public boolean release(String key, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long released = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
        return Long.valueOf(1L).equals(released);
    }
}
