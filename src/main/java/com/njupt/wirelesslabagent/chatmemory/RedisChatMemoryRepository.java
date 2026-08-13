package com.njupt.wirelesslabagent.chatmemory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.njupt.wirelesslabagent.config.ChatMemoryProperties;
import com.njupt.wirelesslabagent.service.ConversationSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Redis 持久化的 ChatMemoryRepository。
 * Key: chat-memory:{conversationId} → Redis List
 * 每个元素是 JSON: {"messageType":"user","text":"..."}
 * 启用条件：配置了 spring.data.redis.host
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat-memory:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;
    private final ChatMemoryProperties properties;
    private final ConversationSummaryService summaryService;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate,
                                     ChatMemoryProperties properties,
                                     ConversationSummaryService summaryService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.summaryService = summaryService;
    }

    private String redisKey(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return List.of();
        return keys.stream()
                .map(k -> k.substring(KEY_PREFIX.length()))
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String key = redisKey(conversationId);
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) return new ArrayList<>();
        redisTemplate.expire(key, properties.getTtl());
        summaryService.touch(conversationId);
        return jsonList.stream()
                .map(this::deserialize)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = redisKey(conversationId);
        List<String> previous = redisTemplate.opsForList().range(key, 0, -1);
        List<String> jsonList = messages.stream()
                .map(this::serialize)
                .toList();
        List<String> evictedJson = findEvictedPrefix(
                previous == null ? List.of() : previous, jsonList);

        // MessageWindowChatMemory 每次传入完整窗口，这里用新窗口替换旧列表。
        redisTemplate.delete(key);
        if (messages.isEmpty()) return;
        redisTemplate.opsForList().rightPushAll(key, jsonList);
        redisTemplate.expire(key, properties.getTtl());

        if (!evictedJson.isEmpty()) {
            List<Message> evictedMessages = evictedJson.stream()
                    .map(this::deserialize)
                    .filter(Objects::nonNull)
                    .toList();
            summaryService.captureEvicted(conversationId, evictedMessages);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(redisKey(conversationId));
        summaryService.delete(conversationId);
    }

    /** 找出旧列表中不再出现在新窗口开头的前缀，即本次被滑动窗口淘汰的消息。 */
    static List<String> findEvictedPrefix(List<String> previous, List<String> current) {
        if (previous.isEmpty()) {
            return List.of();
        }
        int maxOverlap = Math.min(previous.size(), current.size());
        for (int overlap = maxOverlap; overlap >= 0; overlap--) {
            if (previous.subList(previous.size() - overlap, previous.size())
                    .equals(current.subList(0, overlap))) {
                return List.copyOf(previous.subList(0, previous.size() - overlap));
            }
        }
        return List.copyOf(previous);
    }

    // ---------- 序列化/反序列化 ----------

    record MessageWrapper(String messageType, String text) {}

    private String serialize(Message message) {
        String text;
        if (message instanceof ToolResponseMessage trm) {
            text = trm.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        } else {
            text = message.getText();
        }
        try {
            return MAPPER.writeValueAsString(
                    new MessageWrapper(message.getMessageType().getValue(), text));
        } catch (JsonProcessingException e) {
            log.error("序列化消息失败", e);
            return "{}";
        }
    }

    private Message deserialize(String json) {
        try {
            MessageWrapper wrapper = MAPPER.readValue(json, MessageWrapper.class);
            String type = wrapper.messageType();
            String text = wrapper.text() != null ? wrapper.text() : "";
            return switch (type) {
                case "user" -> new UserMessage(text);
                case "assistant" -> new AssistantMessage(text);
                case "system" -> new SystemMessage(text);
                case "tool" -> ToolResponseMessage.builder()
                        .responses(List.of(
                                new ToolResponseMessage.ToolResponse("", "", text)))
                        .build();
                default -> new UserMessage(text);
            };
        } catch (JsonProcessingException e) {
            log.warn("反序列化消息失败，跳过: {}", json);
            return null;
        }
    }
}
