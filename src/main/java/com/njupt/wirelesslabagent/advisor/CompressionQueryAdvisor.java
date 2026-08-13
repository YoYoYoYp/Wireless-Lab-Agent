package com.njupt.wirelesslabagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 查询压缩 Advisor：用 {@link CompressionQueryTransformer} 将"对话历史 + 追问"
 * 压缩为上下文完整的独立查询，解决多轮对话中代词/省略带来的检索失败问题。
 *
 * <p>典型场景：
 * <pre>
 *   第1轮：用户"碧海湾小区在哪？" → AI"在深圳南山区后海"
 *   第2轮：用户"那二手房均价呢？"
 *   压缩后 → "深圳南山区后海碧海湾小区的二手房均价是多少？"
 * </pre>
 */
@Slf4j
public class CompressionQueryAdvisor implements CallAdvisor, StreamAdvisor {

    private final CompressionQueryTransformer transformer;
    private final DocumentRetriever retriever;
    private final QueryAugmenter queryAugmenter;
    private int order = 1;

    public CompressionQueryAdvisor(ChatClient.Builder chatClientBuilder, DocumentRetriever retriever) {
        this.transformer = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        this.retriever = retriever;
        this.queryAugmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();

        // 1. 将"历史 + 追问"压缩为独立查询
        Query original = buildQuery(userText, request);
        Query compressed = RagFallbackSupport.transformOrOriginal(
                "历史查询压缩", original, () -> this.transformer.transform(original));
        log.info("CompressionQuery: {} -> {}", userText, compressed.text());

        // 2. 检索
        List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, compressed);

        // 3. 增强查询（文档上下文注入）→ 继续链条
        ChatClientRequest modified = augmentWhenPresent(request, original, docs);
        ChatClientResponse response = chain.nextCall(modified);
        response.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, docs);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();
        Query original = buildQuery(userText, request);
        Query compressed = RagFallbackSupport.transformOrOriginal(
                "历史查询压缩", original, () -> this.transformer.transform(original));
        List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, compressed);

        ChatClientRequest modified = augmentWhenPresent(request, original, docs);
        return chain.nextStream(modified)
                .doOnNext(resp -> resp.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, docs));
    }

    private Query buildQuery(String userText, ChatClientRequest request) {
        List<Message> history = new ArrayList<>();
        Object summary = request.context().get(ConversationSummaryAdvisor.CONTEXT_KEY);
        if (summary != null && !summary.toString().isBlank()) {
            history.add(new UserMessage("早期对话记忆：\n" + summary));
        }
        history.addAll(request.prompt().getInstructions().stream()
                .filter(m -> m.getMessageType() != MessageType.SYSTEM)
                .collect(Collectors.toList()));
        return Query.builder().text(userText).history(history).build();
    }

    private ChatClientRequest augmentWhenPresent(ChatClientRequest request, Query query, List<Document> docs) {
        if (docs.isEmpty()) return request;
        Query augmented = this.queryAugmenter.augment(query, docs);
        return request.mutate().prompt(request.prompt().augmentUserMessage(augmented.text())).build();
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    public CompressionQueryAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
