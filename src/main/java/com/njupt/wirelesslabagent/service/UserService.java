package com.njupt.wirelesslabagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String USER_PREFIX = "user:";
    private static final String TOKEN_PREFIX = "auth:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public boolean register(String username, String password) {
        String key = USER_PREFIX + username;
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            return false;
        }
        redis.opsForValue().set(key, password);
        log.info("用户注册成功: {}", username);
        return true;
    }

    public String login(String username, String password) {
        String key = USER_PREFIX + username;
        String storedPwd = redis.opsForValue().get(key);
        if (storedPwd == null || !storedPwd.equals(password)) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(TOKEN_PREFIX + token, username, TOKEN_TTL);
        log.info("用户登录成功: {}", username);
        return token;
    }

    public String validateToken(String token) {
        String key = TOKEN_PREFIX + token;
        String username = redis.opsForValue().get(key);
        if (username != null) {
            // 刷新 TTL
            redis.expire(key, TOKEN_TTL);
        }
        return username;
    }
}
