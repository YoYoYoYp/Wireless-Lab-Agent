package com.njupt.wirelesslabagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.function.Supplier;

/** RAG 各阶段的降级策略，确保预处理、检索或精排失败时仍可继续回答。 */
@Slf4j
public final class
RagFallbackSupport {

    private RagFallbackSupport() {
    }

    public static Query transformOrOriginal(String stage, Query original, Supplier<Query> transformer) {
        try {
            Query transformed = transformer.get();
            return transformed == null || transformed.text().isBlank() ? original : transformed;
        } catch (Exception exception) {
            log.warn("{} 失败，降级为原始查询: {}", stage, exception.getMessage());
            return original;
        }
    }

    public static List<Query> expandOrOriginal(Query original, Supplier<List<Query>> expander) {
        try {
            List<Query> queries = expander.get();
            return queries == null || queries.isEmpty() ? List.of(original) : queries;
        } catch (Exception exception) {
            log.warn("多查询扩展失败，降级为单查询: {}", exception.getMessage());
            return List.of(original);
        }
    }

    public static List<Document> retrieveOrEmpty(DocumentRetriever retriever, Query query) {
        try {
            List<Document> documents = retriever.retrieve(query);
            return documents == null ? List.of() : documents;
        } catch (Exception exception) {
            log.warn("向量检索失败，降级为无 RAG 直接回答: {}", exception.getMessage());
            return List.of();
        }
    }

    public static List<Document> rerankOrOriginal(DocumentPostProcessor reranker,
                                                   Query query,
                                                   List<Document> documents) {
        if (reranker == null || documents.isEmpty()) {
            return documents;
        }
        try {
            List<Document> reranked = reranker.process(query, documents);
            return reranked == null || reranked.isEmpty() ? documents : reranked;
        } catch (Exception exception) {
            log.warn("精排失败，保留向量粗排结果: {}", exception.getMessage());
            return documents;
        }
    }
}
