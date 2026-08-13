package com.njupt.wirelesslabagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class KnowledgeDocumentService {

    private static final String QUEUE_KEY = "knowledge:ingest:queue";
    private static final String RESULT_PREFIX = "knowledge:ingest:result:";
    private static final Duration RESULT_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final KnowledgeMetadataResolver metadataResolver;
    private final ExecutorService consumer = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    public KnowledgeDocumentService(StringRedisTemplate redis,
                                    VectorStore vectorStore,
                                    ChatModel chatModel,
                                    ObjectMapper objectMapper,
                                    KnowledgeMetadataResolver metadataResolver) {
        this.redis = redis;
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.metadataResolver = metadataResolver;
    }

    @PostConstruct
    public void startConsumer() {
        consumer.submit(this::consumeLoop);
        log.info("知识文档异步消费者已启动: queue={}", QUEUE_KEY);
    }

    @PreDestroy
    public void stopConsumer() {
        running = false;
        consumer.shutdownNow();
    }

    public String submit(Path file,
                         String originalName,
                         String userId,
                         String category,
                         String deviceType,
                         String documentType,
                         String chapter) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> task = Map.of(
                "taskId", taskId,
                "filePath", file.toAbsolutePath().toString(),
                "source", originalName,
                "userId", valueOrDefault(userId, "anonymous"),
                "category", valueOrDefault(category, "自动识别"),
                "deviceType", valueOrDefault(deviceType, "自动识别"),
                "documentType", valueOrDefault(documentType, "自动识别"),
                "chapter", valueOrDefault(chapter, "自动识别")
        );
        try {
            saveResult(taskId, new IngestionResult("processing", originalName, 0, "等待后台解析与向量化"));
            redis.opsForList().leftPush(QUEUE_KEY, objectMapper.writeValueAsString(task));
            return taskId;
        } catch (Exception exception) {
            throw new IllegalStateException("知识入库任务提交失败", exception);
        }
    }

    public IngestionResult getResult(String taskId) {
        String json = redis.opsForValue().get(RESULT_PREFIX + taskId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IngestionResult.class);
        } catch (Exception exception) {
            return new IngestionResult("error", "unknown", 0, "任务结果解析失败");
        }
    }

    private void consumeLoop() {
        while (running) {
            try {
                String json = redis.opsForList().rightPop(QUEUE_KEY, 5, TimeUnit.SECONDS);
                if (json == null) {
                    continue;
                }
                Map<String, String> task = objectMapper.readValue(json, new TypeReference<>() {});
                String taskId = task.get("taskId");
                try {
                    saveResult(taskId, ingest(task));
                } catch (Exception exception) {
                    log.error("知识文档入库失败: taskId={}", taskId, exception);
                    saveResult(taskId, new IngestionResult(
                            "error", task.get("source"), 0, "入库失败: " + exception.getMessage()));
                } finally {
                    Files.deleteIfExists(Path.of(task.get("filePath")));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                log.error("知识文档消费者异常", exception);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private IngestionResult ingest(Map<String, String> task) {
        Path path = Path.of(task.get("filePath"));
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("待处理文件不存在");
        }

        List<Document> parsed = new ArrayList<>(
                new TikaDocumentReader(new FileSystemResource(path)).read());
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("文档没有可解析内容");
        }

        String fullText = parsed.stream()
                .map(Document::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        var metadata = metadataResolver.resolve(
                task.get("source"),
                fullText,
                task.get("category"),
                task.get("deviceType"),
                task.get("documentType"),
                task.get("chapter")
        );
        parsed.forEach(document -> metadata.applyTo(
                document, task.get("source"), task.get("userId"), "upload"));

        TokenTextSplitter splitter = new TokenTextSplitter(500, 100, 5, 300, true);
        List<Document> chunks = splitter.apply(parsed);
        List<Document> enriched = chunks;
        try {
            enriched = new KeywordMetadataEnricher(chatModel, 5).apply(chunks);
        } catch (Exception exception) {
            log.warn("关键词增强失败，使用原始切片继续入库: {}", exception.getMessage());
        }

        int batchSize = 10;
        for (int index = 0; index < enriched.size(); index += batchSize) {
            vectorStore.add(enriched.subList(index, Math.min(index + batchSize, enriched.size())));
        }
        return new IngestionResult("completed", task.get("source"), chunks.size(),
                "知识文档已写入向量库，分类：" + metadata.category());
    }

    private void saveResult(String taskId, IngestionResult result) throws Exception {
        redis.opsForValue().set(
                RESULT_PREFIX + taskId,
                objectMapper.writeValueAsString(result),
                RESULT_TTL
        );
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record IngestionResult(String status, String source, int chunkCount, String message) {
    }
}
