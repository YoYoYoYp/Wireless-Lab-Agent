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
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询重写 Advisor：用 {@link RewriteQueryTransformer} 将模糊/口语化的用户问题
 * 重写为结构化、明确的查询，再检索向量库，提高检索精度。
 *
 * <p>对比 MultiQueryExpansionAdvisor（多查询扩展）：
 * <pre>
 *   查询重写:    用户问题 → [LLM 重写为 1 个精准查询] → [向量库检索 1 次] → LLM
 *   多查询扩展:  用户问题 → [LLM 扩写为 N 个变体] → [向量库检索 N+1 次] → 去重 → LLM
 * </pre>
 */
@Slf4j
public class RewriteQueryAdvisor implements CallAdvisor, StreamAdvisor {

    private final RewriteQueryTransformer queryTransformer;
    private final DocumentRetriever retriever;
    private final QueryAugmenter queryAugmenter;
    private int order = 1;

    public RewriteQueryAdvisor(ChatClient.Builder chatClientBuilder, DocumentRetriever retriever) {
        this.queryTransformer = RewriteQueryTransformer.builder()
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

        Query original = buildQuery(userText, request);
        Query rewritten = RagFallbackSupport.transformOrOriginal(
                "查询重写", original, () -> this.queryTransformer.transform(original));
        log.info("RewriteQuery: {} -> {}", userText, rewritten.text());

        List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, rewritten);
        log.info("RewriteQuery: 检索到 {} 个文档", docs.size());

        ChatClientRequest modified = augmentWhenPresent(request, original, docs);
        ChatClientResponse response = chain.nextCall(modified);
        response.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, docs);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();
        Query original = buildQuery(userText, request);
        Query rewritten = RagFallbackSupport.transformOrOriginal(
                "查询重写", original, () -> this.queryTransformer.transform(original));
        List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, rewritten);

        ChatClientRequest modified = augmentWhenPresent(request, original, docs);
        return chain.nextStream(modified)
                .doOnNext(resp -> resp.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, docs));
    }

    private Query buildQuery(String userText, ChatClientRequest request) {
        List<Message> history = request.prompt().getInstructions().stream()
                .filter(m -> m.getMessageType() != MessageType.SYSTEM)
                .collect(Collectors.toList());
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

    public RewriteQueryAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
