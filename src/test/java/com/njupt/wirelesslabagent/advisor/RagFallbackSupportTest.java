package com.njupt.wirelesslabagent.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagFallbackSupportTest {

    private final Query original = Query.builder().text("原始问题").build();

    @Test
    void shouldFallbackToOriginalQueryWhenTransformationFails() {
        Query result = RagFallbackSupport.transformOrOriginal(
                "rewrite", original, () -> { throw new IllegalStateException("LLM error"); });

        assertEquals(original, result);
    }

    @Test
    void shouldFallbackToSingleQueryWhenExpansionFails() {
        List<Query> result = RagFallbackSupport.expandOrOriginal(
                original, () -> { throw new IllegalStateException("LLM error"); });

        assertEquals(List.of(original), result);
    }

    @Test
    void shouldFallbackToEmptyContextWhenRetrievalFails() {
        DocumentRetriever retriever = query -> { throw new IllegalStateException("PG down"); };

        assertEquals(List.of(), RagFallbackSupport.retrieveOrEmpty(retriever, original));
    }

    @Test
    void shouldKeepCoarseResultsWhenRerankFails() {
        List<Document> coarse = List.of(new Document("候选知识"));
        DocumentPostProcessor reranker = (query, documents) -> {
            throw new IllegalStateException("rerank down");
        };

        assertEquals(coarse, RagFallbackSupport.rerankOrOriginal(reranker, original, coarse));
    }
}
