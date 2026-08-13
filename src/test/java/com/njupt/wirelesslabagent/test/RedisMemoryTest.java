package com.njupt.wirelesslabagent.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.njupt.wirelesslabagent.chatmemory.RedisChatMemoryRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@Slf4j
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisMemoryTest {

    @Resource
    private RedisChatMemoryRepository repository;

    @Resource
    private StringRedisTemplate redisTemplate;

    private static final ObjectMapper mapper = new ObjectMapper();

    public void testAll() {
        testCrud();
        testWithWindowChatMemory();
        testJsonFormat();
    }

    private void testCrud() {
        log.info("=== 测试1：RedisChatMemoryRepository CRUD ===");

        String chatId = "redis-test-crud";

        repository.saveAll(chatId, List.of(
                new UserMessage("Redis 测试：我叫张三"),
                new AssistantMessage("Redis 测试：你好张三，记住了")
        ));

        List<Message> messages = repository.findByConversationId(chatId);
        log.info("读取到 {} 条消息", messages.size());
        messages.forEach(m -> log.info("  [{}] {}", m.getMessageType(), m.getText()));

        String key = "chat-memory:" + chatId;
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        log.info("Redis Key: {}", key);
        log.info("Redis List 长度: {}", raw != null ? raw.size() : 0);

        List<String> ids = repository.findConversationIds();
        log.info("当前所有会话 ID: {}", ids);

        repository.deleteByConversationId(chatId);
        boolean deleted = redisTemplate.opsForList().range(key, 0, -1) == null
                || redisTemplate.opsForList().range(key, 0, -1).isEmpty();
        log.info("删除后 Key 存在? {}", !deleted);
    }

    private void testWithWindowChatMemory() {
        log.info("=== 测试2：MessageWindowChatMemory + RedisChatMemoryRepository ===");

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(4)
                .build();

        String chatId = "redis-test-window";

        chatMemory.add(chatId, List.of(
                new UserMessage("第1轮问题"),
                new AssistantMessage("第1轮回答")
        ));
        chatMemory.add(chatId, List.of(
                new UserMessage("第2轮问题"),
                new AssistantMessage("第2轮回答")
        ));
        chatMemory.add(chatId, List.of(
                new UserMessage("第3轮问题"),
                new AssistantMessage("第3轮回答")
        ));

        List<Message> all = chatMemory.get(chatId);
        log.info("窗口测试: 共 {} 条消息（预期 4 条，第1轮被淘汰）", all.size());
        all.forEach(m -> log.info("  [{}] {}", m.getMessageType(), m.getText()));

        chatMemory.clear(chatId);
    }

    private void testJsonFormat() {
        log.info("=== 测试3：JSON 序列化格式 ===");

        String chatId = "redis-test-json";

        repository.saveAll(chatId, List.of(
                new SystemMessage("系统提示词"),
                new UserMessage("用户消息"),
                new AssistantMessage("AI 回复")
        ));

        String key = "chat-memory:" + chatId;
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw != null) {
            raw.forEach(json -> {
                try {
                    Object parsed = mapper.readValue(json, Object.class);
                    String pretty = mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(parsed);
                    log.info("JSON:\n{}", pretty);
                } catch (JsonProcessingException e) {
                    log.error("非法 JSON: {}", json);
                }
            });
        }

        repository.deleteByConversationId(chatId);
    }
}
