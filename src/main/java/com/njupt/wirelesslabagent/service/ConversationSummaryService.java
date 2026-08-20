package com.njupt.wirelesslabagent.service;

import com.njupt.wirelesslabagent.config.ChatMemoryProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将滑出活跃窗口的消息保存为“待摘要原文”，并按批次异步合并为滚动摘要。
 */
@Slf4j
@Service
public class ConversationSummaryService {

    private static final String SUMMARY_PREFIX = "chat-summary:";
    private static final String PENDING_PREFIX = "chat-summary-pending:";
    private static final String LOCK_PREFIX = "chat-summary-lock:";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final RedisLockService redisLockService;
    private final ChatMemoryProperties properties;
    private final ChatClient summaryClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat-summary-", 0).factory());

    public ConversationSummaryService(StringRedisTemplate redisTemplate,
                                      RedisLockService redisLockService,
                                      ChatMemoryProperties properties,
                                      ChatModel chatModel) {
        this.redisTemplate = redisTemplate;
        this.redisLockService = redisLockService;
        this.properties = properties;
        this.summaryClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你负责维护无线实验室对话的长期记忆。请把已有摘要与新增旧消息合并成一份简洁、准确的中文摘要。
                        必须保留：用户目标、设备与频率等关键参数、已执行操作及结果、重要结论、未解决问题和安全限制。
                        不要补充原文没有的信息，不要输出标题、解释或 Markdown。
                        """)
                .build();
    }

    public void captureEvicted(String conversationId, List<Message> evictedMessages) {
        List<String> memoryLines = evictedMessages.stream()
                .filter(message -> message.getMessageType() != MessageType.SYSTEM)
                .map(this::toMemoryLine)
                .filter(line -> !line.isBlank())
                .toList();
        if (memoryLines.isEmpty()) {
            return;
        }

        String pendingKey = pendingKey(conversationId);
        redisTemplate.opsForList().rightPushAll(pendingKey, memoryLines);
        refreshTtl(pendingKey);

        Long pendingSize = redisTemplate.opsForList().size(pendingKey);
        if (pendingSize != null && pendingSize >= properties.getSummaryBatchSize()) {
            executor.submit(() -> summarizePending(conversationId));
        }
    }

    /**
     * 返回提供给模型的早期记忆。未达到摘要批次的原文也会包含在内，防止信息断层。
     */
    public String buildContext(String conversationId) {
        String summaryKey = summaryKey(conversationId);
        String pendingKey = pendingKey(conversationId);
        String summary = redisTemplate.opsForValue().get(summaryKey);
        List<String> pending = redisTemplate.opsForList().range(pendingKey, 0, -1);

        if (summary != null) {
            refreshTtl(summaryKey);
        }
        if (pending != null && !pending.isEmpty()) {
            refreshTtl(pendingKey);
        }

        List<String> sections = new ArrayList<>();
        if (summary != null && !summary.isBlank()) {
            sections.add("已压缩的早期记忆：\n" + summary);
        }
        if (pending != null && !pending.isEmpty()) {
            sections.add("尚未压缩的早期消息：\n" + String.join("\n", pending));
        }
        return String.join("\n\n", sections);
    }

    public void delete(String conversationId) {
        redisTemplate.delete(List.of(
                summaryKey(conversationId),
                pendingKey(conversationId),
                lockKey(conversationId)));
    }

    public void touch(String conversationId) {
        refreshTtl(summaryKey(conversationId));
        refreshTtl(pendingKey(conversationId));
    }

    private void summarizePending(String conversationId) {
        String lockKey = lockKey(conversationId);
        String lockToken = redisLockService.tryAcquire(lockKey, LOCK_TTL);
        if (lockToken == null) {
            return;
        }

        try {
            while (pendingSize(conversationId) >= properties.getSummaryBatchSize()) {
                summarizeOneBatch(conversationId);
            }
        } catch (Exception exception) {
            log.warn("滚动摘要生成失败，旧消息继续保留在待摘要区: {}", exception.getMessage());
        } finally {
            redisLockService.release(lockKey, lockToken);
        }
    }

    private void summarizeOneBatch(String conversationId) {
        int batchSize = properties.getSummaryBatchSize();
        String pendingKey = pendingKey(conversationId);
        List<String> batch = redisTemplate.opsForList().range(pendingKey, 0, batchSize - 1L);
        if (batch == null || batch.size() < batchSize) {
            return;
        }

        String previous = redisTemplate.opsForValue().get(summaryKey(conversationId));
        String result = summaryClient.prompt()
                .user("""
                        已有摘要：
                        %s

                        新增旧消息：
                        %s

                        请合并并控制在 %d 个中文字符以内：
                        """.formatted(
                        previous == null || previous.isBlank() ? "（无）" : previous,
                        String.join("\n", batch),
                        properties.getSummaryMaxChars()))
                .call()
                .content();

        if (result == null || result.isBlank()) {
            throw new IllegalStateException("摘要模型返回空内容");
        }
        String limitedResult = result.length() > properties.getSummaryMaxChars()
                ? result.substring(0, properties.getSummaryMaxChars())
                : result;
        redisTemplate.opsForValue().set(summaryKey(conversationId), limitedResult, properties.getTtl());
        redisTemplate.opsForList().trim(pendingKey, batch.size(), -1);
        refreshTtl(pendingKey);
    }

    private long pendingSize(String conversationId) {
        Long size = redisTemplate.opsForList().size(pendingKey(conversationId));
        return size == null ? 0L : size;
    }

    private String toMemoryLine(Message message) {
        String role = switch (message.getMessageType()) {
            case USER -> "用户";
            case ASSISTANT -> "助手";
            case TOOL -> "工具结果";
            default -> message.getMessageType().getValue();
        };
        String text = message.getText();
        return text == null || text.isBlank() ? "" : role + "：" + text;
    }

    private void refreshTtl(String key) {
        redisTemplate.expire(key, properties.getTtl());
    }

    private String summaryKey(String conversationId) {
        return SUMMARY_PREFIX + conversationId;
    }

    private String pendingKey(String conversationId) {
        return PENDING_PREFIX + conversationId;
    }

    private String lockKey(String conversationId) {
        return LOCK_PREFIX + conversationId;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
