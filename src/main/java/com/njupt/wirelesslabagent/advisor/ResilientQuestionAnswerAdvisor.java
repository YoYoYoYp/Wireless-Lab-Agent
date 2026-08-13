package com.njupt.wirelesslabagent.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import reactor.core.publisher.Flux;

import java.util.List;

/** 单查询 RAG：检索失败或无命中时自动降级为直接 LLM 回答。 */
public class ResilientQuestionAnswerAdvisor implements CallAdvisor, StreamAdvisor {

    private final DocumentRetriever retriever;
    private final QueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
            .allowEmptyContext(true)
            .build();
    private int order = 1;

    public ResilientQuestionAnswerAdvisor(DocumentRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Query query = Query.builder().text(request.prompt().getUserMessage().getText()).build();
        List<Document> documents = RagFallbackSupport.retrieveOrEmpty(retriever, query);
        ChatClientRequest modified = augmentWhenPresent(request, query, documents);
        ChatClientResponse response = chain.nextCall(modified);
        response.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, documents);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Query query = Query.builder().text(request.prompt().getUserMessage().getText()).build();
        List<Document> documents = RagFallbackSupport.retrieveOrEmpty(retriever, query);
        return chain.nextStream(augmentWhenPresent(request, query, documents))
                .doOnNext(response -> response.context()
                        .put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, documents));
    }

    private ChatClientRequest augmentWhenPresent(ChatClientRequest request,
                                                 Query query,
                                                 List<Document> documents) {
        if (documents.isEmpty()) {
            return request;
        }
        Query augmented = queryAugmenter.augment(query, documents);
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(augmented.text()))
                .build();
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    public ResilientQuestionAnswerAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
