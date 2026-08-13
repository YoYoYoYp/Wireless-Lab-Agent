package com.njupt.wirelesslabagent.config;

import com.alibaba.cloud.ai.model.RerankModel;
import com.njupt.wirelesslabagent.advisor.*;
import com.njupt.wirelesslabagent.common.RagStrategy;
import com.njupt.wirelesslabagent.service.ConversationSummaryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

import static org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder;

@Component
public class ChatClientFactory {

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;
    private final RerankModel rerankModel;
    private final ConversationSummaryService summaryService;

    public ChatClientFactory(ChatModel chatModel, ChatMemoryRepository repository,
                             VectorStore vectorStore,
                             ChatMemoryProperties memoryProperties,
                             ConversationSummaryService summaryService,
                             @Autowired(required = false) RerankModel rerankModel) {
        this.chatModel = chatModel;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(memoryProperties.getWindowSize())
                .build();
        this.vectorStore = vectorStore;
        this.rerankModel = rerankModel;
        this.summaryService = summaryService;
    }

    /**
     * 一次性构建所有策略对应的 ChatClient，存入 Map。
     * WirelessLabAgentApp 按策略直接取用，避免构造器膨胀。
     */
    public Map<RagStrategy, ChatClient> buildAll(String systemPrompt) {
        Map<RagStrategy, ChatClient> map = new EnumMap<>(RagStrategy.class);

        // --- 共享的基础 Advisor（permission → summary → memory → RE2 → log） ---
        var permission = new PermissionAdvisor().withOrder(-2);
        var summaryAdvisor = new ConversationSummaryAdvisor(summaryService).withOrder(-1);
        var memoryAdvisor = builder(chatMemory).order(0).build();
        var re2 = new ReReadingAdvisor().withOrder(2);
        var logAdvisor = new MyLogAdvisor().withOrder(3);

        // NONE: 纯对话 + 工具调用（不加 RE2，避免干扰 Function Calling）
        map.put(RagStrategy.NONE, ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(permission, summaryAdvisor, memoryAdvisor, logAdvisor)
                .build());

        // SINGLE: 单查询 RAG
        map.put(RagStrategy.SINGLE, ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(permission, summaryAdvisor, memoryAdvisor,
                        new ResilientQuestionAnswerAdvisor(
                                VectorStoreDocumentRetriever.builder()
                                        .vectorStore(vectorStore)
                                        .similarityThreshold(0.5).topK(3)
                                        .build()).withOrder(1),
                        re2, logAdvisor)
                .build());

        // 共享的精确检索器（topK=3, threshold=0.5）
        DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.5).topK(3)
                .build();

        // REWRITE: LLM 重写查询
        map.put(RagStrategy.REWRITE, ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(permission, summaryAdvisor, memoryAdvisor,
                        new RewriteQueryAdvisor(ChatClient.builder(chatModel), retriever).withOrder(1),
                        re2, logAdvisor)
                .build());

        // TRANSLATE: LLM 翻译查询
        map.put(RagStrategy.TRANSLATE, ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(permission, summaryAdvisor, memoryAdvisor,
                        new TranslationQueryAdvisor(ChatClient.builder(chatModel), retriever, "chinese").withOrder(1),
                        re2, logAdvisor)
                .build());

        // COMPRESS: 对话历史压缩为独立查询
        map.put(RagStrategy.COMPRESS, ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(permission, summaryAdvisor, memoryAdvisor,
                        new CompressionQueryAdvisor(ChatClient.builder(chatModel), retriever).withOrder(1),
                        re2, logAdvisor)
                .build());

        // MULTI: 多查询扩展 + 粗排精排
        DocumentRetriever coarseRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.35).topK(5)
                .build();
        RerankingDocumentPostProcessor reranker = rerankModel != null
                ? new RerankingDocumentPostProcessor(rerankModel, 3) : null;

        map.put(RagStrategy.MULTI, ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(permission, summaryAdvisor, memoryAdvisor,
                        new MultiQueryExpansionAdvisor(ChatClient.builder(chatModel), coarseRetriever, 3, reranker)
                                .withOrder(1),
                        re2, logAdvisor)
                .build());

        return map;
    }
}
