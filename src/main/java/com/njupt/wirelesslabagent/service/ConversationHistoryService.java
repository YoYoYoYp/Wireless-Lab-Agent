package com.njupt.wirelesslabagent.service;

import com.njupt.wirelesslabagent.chatmemory.ConversationKeyFactory;
import com.njupt.wirelesslabagent.config.ChatMemoryProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ConversationHistoryService {

    private static final String USER_INDEX_PREFIX = "user:conv:index:";
    private static final String TITLE_PREFIX = "chat-title:";

    private final StringRedisTemplate redis;
    private final ConversationKeyFactory keyFactory;
    private final ChatMemoryProperties properties;

    public ConversationHistoryService(StringRedisTemplate redis,
                                      ConversationKeyFactory keyFactory,
                                      ChatMemoryProperties properties) {
        this.redis = redis;
        this.keyFactory = keyFactory;
        this.properties = properties;
    }

    public void track(String userId, String chatId, String firstMessage) {
        String indexKey = USER_INDEX_PREFIX + userId;
        double now = Instant.now().toEpochMilli();
        redis.opsForZSet().add(indexKey, chatId, now);
        redis.expire(indexKey, properties.getTtl());

        String titleKey = TITLE_PREFIX + keyFactory.scopedConversationId(userId, chatId);
        String title = titleOf(firstMessage);
        redis.opsForValue().setIfAbsent(titleKey, title, properties.getTtl());
        redis.expire(titleKey, properties.getTtl());
    }

    public List<ConversationEntry> list(String userId) {
        String indexKey = USER_INDEX_PREFIX + userId;
        removeExpiredEntries(indexKey);
        Set<String> chatIds = redis.opsForZSet().reverseRange(indexKey, 0, -1);
        if (chatIds == null || chatIds.isEmpty()) {
            return List.of();
        }

        List<ConversationEntry> result = new ArrayList<>();
        for (String chatId : chatIds) {
            String scopedId = keyFactory.scopedConversationId(userId, chatId);
            String title = redis.opsForValue().get(TITLE_PREFIX + scopedId);
            result.add(new ConversationEntry(chatId, title == null ? "新对话" : title));
        }
        redis.expire(indexKey, properties.getTtl());
        return result;
    }

    public boolean isOwnedBy(String userId, String chatId) {
        String indexKey = USER_INDEX_PREFIX + userId;
        Double score = redis.opsForZSet().score(indexKey, chatId);
        return score != null && score >= expirationCutoff();
    }

    public void touch(String userId, String chatId) {
        if (isOwnedBy(userId, chatId)) {
            track(userId, chatId, null);
        }
    }

    private void removeExpiredEntries(String indexKey) {
        redis.opsForZSet().removeRangeByScore(indexKey, 0, expirationCutoff());
    }

    private double expirationCutoff() {
        return Instant.now().minus(properties.getTtl()).toEpochMilli();
    }

    private String titleOf(String message) {
        if (message == null || message.isBlank()) {
            return "新对话";
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        return normalized.length() > 30 ? normalized.substring(0, 30) + "..." : normalized;
    }

    public record ConversationEntry(String chatId, String title) {
    }
}
