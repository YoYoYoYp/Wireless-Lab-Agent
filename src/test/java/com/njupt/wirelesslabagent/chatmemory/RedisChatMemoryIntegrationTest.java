package com.njupt.wirelesslabagent.chatmemory;

import com.njupt.wirelesslabagent.config.ChatMemoryProperties;
import com.njupt.wirelesslabagent.service.ConversationHistoryService;
import com.njupt.wirelesslabagent.service.ConversationSummaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TESTS", matches = "true")
class RedisChatMemoryIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private ChatMemoryProperties properties;
    private ConversationSummaryService summaryService;
    private RedisChatMemoryRepository repository;
    private ConversationHistoryService historyService;
    private ConversationKeyFactory keyFactory;
    private ChatMemory chatMemory;
    private String userA;
    private String userB;
    private String chatId;
    private String scopedA;
    private String scopedB;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        properties = new ChatMemoryProperties();
        properties.setWindowSize(20);
        properties.setSummaryBatchSize(100);
        properties.setTtl(Duration.ofMinutes(5));

        keyFactory = new ConversationKeyFactory();
        ChatModel summaryModel = mock(ChatModel.class);
        when(summaryModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("已生成的滚动摘要")))));
        summaryService = new ConversationSummaryService(redis, properties, summaryModel);
        repository = new RedisChatMemoryRepository(redis, properties, summaryService);
        historyService = new ConversationHistoryService(redis, keyFactory, properties);
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.getWindowSize())
                .build();

        String suffix = UUID.randomUUID().toString();
        userA = "it-user-a-" + suffix;
        userB = "it-user-b-" + suffix;
        chatId = "same-chat";
        scopedA = keyFactory.scopedConversationId(userA, chatId);
        scopedB = keyFactory.scopedConversationId(userB, chatId);
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            List<String> keys = new ArrayList<>();
            for (String scopedId : List.of(scopedA, scopedB)) {
                keys.add("chat-memory:" + scopedId);
                keys.add("chat-summary:" + scopedId);
                keys.add("chat-summary-pending:" + scopedId);
                keys.add("chat-summary-lock:" + scopedId);
                keys.add("chat-title:" + scopedId);
            }
            keys.add("user:conv:index:" + userA);
            keys.add("user:conv:index:" + userB);
            redis.delete(keys);
        }
        if (summaryService != null) {
            summaryService.shutdown();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldPersistIsolatedWindowEvictionAndTtlInRealRedis() {
        assertNotEquals(scopedA, scopedB);

        for (int i = 0; i < 21; i++) {
            chatMemory.add(scopedA, new UserMessage("message-" + i));
        }
        chatMemory.add(scopedB, new UserMessage("other-user-message"));
        historyService.track(userA, chatId, "用户 A 的实验");
        historyService.track(userB, chatId, "用户 B 的实验");

        var userAMessages = repository.findByConversationId(scopedA);
        var userBMessages = repository.findByConversationId(scopedB);
        assertEquals(20, userAMessages.size());
        assertEquals("message-1", userAMessages.getFirst().getText());
        assertEquals("message-20", userAMessages.getLast().getText());
        assertEquals(1, userBMessages.size());
        assertEquals("other-user-message", userBMessages.getFirst().getText());

        String pendingKey = "chat-summary-pending:" + scopedA;
        assertEquals(List.of("用户：message-0"), redis.opsForList().range(pendingKey, 0, -1));
        assertTrue(summaryService.buildContext(scopedA).contains("message-0"));

        assertEquals("用户 A 的实验", historyService.list(userA).getFirst().title());
        assertEquals("用户 B 的实验", historyService.list(userB).getFirst().title());

        assertHasTtl("chat-memory:" + scopedA);
        assertHasTtl(pendingKey);
        assertHasTtl("user:conv:index:" + userA);
        assertHasTtl("chat-title:" + scopedA);
    }

    @Test
    void shouldGenerateRollingSummaryAndRemoveProcessedPendingMessages() {
        properties.setSummaryBatchSize(2);
        summaryService.captureEvicted(scopedA, List.of(
                new UserMessage("旧问题"),
                new AssistantMessage("旧回答")));

        String summaryKey = "chat-summary:" + scopedA;
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!Boolean.TRUE.equals(redis.hasKey(summaryKey))) {
                Thread.sleep(50);
            }
        });

        assertEquals("已生成的滚动摘要", redis.opsForValue().get(summaryKey));
        assertEquals(0L, redis.opsForList().size("chat-summary-pending:" + scopedA));
        assertTrue(summaryService.buildContext(scopedA).contains("已生成的滚动摘要"));
        assertHasTtl(summaryKey);
    }

    private void assertHasTtl(String key) {
        Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(ttlSeconds != null && ttlSeconds > 0 && ttlSeconds <= 300,
                () -> key + " TTL 异常: " + ttlSeconds);
    }
}
