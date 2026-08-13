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
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多查询扩展 Advisor：用 {@link MultiQueryExpander} 将用户问题扩写为多个变体，
 * 多路检索向量库后去重合并，注入 Prompt 上下文。
 *
 * <p>对比 QuestionAnswerAdvisor（单查询检索）：
 * <pre>
 *   单查询:  用户问题 → [向量库检索 1 次] → 拼接上下文 → LLM
 *   多查询:  用户问题 → [LLM 扩写 N 个变体] → [向量库检索 N+1 次] → 去重 → 拼接上下文 → LLM
 * </pre>
 */
@Slf4j
public class MultiQueryExpansionAdvisor implements CallAdvisor, StreamAdvisor {

    private final MultiQueryExpander queryExpander;
    private final DocumentJoiner documentJoiner;
    private final DocumentRetriever retriever;
    private final DocumentPostProcessor reranker;
    private final QueryAugmenter queryAugmenter;
    private int order = 1;

    public MultiQueryExpansionAdvisor(ChatClient.Builder chatClientBuilder, DocumentRetriever retriever,
                                      int queryCount, DocumentPostProcessor reranker) {
        this.queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(queryCount)
                .build();
        this.documentJoiner = new ConcatenationDocumentJoiner();
        this.retriever = retriever;
        this.reranker = reranker;
        this.queryAugmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();

        Query original = buildQuery(userText, request);
        List<Query> queries = RagFallbackSupport.expandOrOriginal(
                original, () -> this.queryExpander.expand(original));

        // 粗排：多路检索（低阈值多召回）
        Map<Query, List<List<Document>>> documentsForQuery = new LinkedHashMap<>();
        for (Query q : queries) {
            List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, q);
            documentsForQuery.put(q, List.of(docs));
        }
        List<Document> allDocs = this.documentJoiner.join(documentsForQuery);
        log.info("MultiQueryExpansion: {} 个查询 → {} 个去重文档（粗排）", queries.size(), allDocs.size());

        // 精排：LLM 重打分 → 截断
        if (this.reranker != null) {
            allDocs = RagFallbackSupport.rerankOrOriginal(this.reranker, original, allDocs);
            log.info("MultiQueryExpansion: 精排后 → {} 个文档", allDocs.size());
        }

        ChatClientRequest modified = augmentWhenPresent(request, original, allDocs);
        ChatClientResponse response = chain.nextCall(modified);
        response.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, allDocs);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();
        Query original = buildQuery(userText, request);
        List<Query> queries = RagFallbackSupport.expandOrOriginal(
                original, () -> this.queryExpander.expand(original));

        Map<Query, List<List<Document>>> documentsForQuery = new LinkedHashMap<>();
        for (Query q : queries) {
            List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, q);
            documentsForQuery.put(q, List.of(docs));
        }
        List<Document> mergedDocs = this.documentJoiner.join(documentsForQuery);
        List<Document> finalDocs = RagFallbackSupport.rerankOrOriginal(
                this.reranker, original, mergedDocs);

        ChatClientRequest modified = augmentWhenPresent(request, original, finalDocs);
        return chain.nextStream(modified)
                .doOnNext(resp -> resp.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, finalDocs));
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

    public MultiQueryExpansionAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
