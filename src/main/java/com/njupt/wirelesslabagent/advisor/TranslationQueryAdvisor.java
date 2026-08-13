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
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询翻译 Advisor：用 {@link TranslationQueryTransformer} 将用户查询翻译为目标语言后检索。
 * 适用于知识库语言与用户输入语言不一致的跨语言检索场景。
 */
@Slf4j
public class TranslationQueryAdvisor implements CallAdvisor, StreamAdvisor {

    private final TranslationQueryTransformer transformer;
    private final DocumentRetriever retriever;
    private final QueryAugmenter queryAugmenter;
    private int order = 1;

    public TranslationQueryAdvisor(ChatClient.Builder chatClientBuilder, DocumentRetriever retriever,
                                   String targetLanguage) {
        this.transformer = TranslationQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .targetLanguage(targetLanguage)
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
        Query translated = RagFallbackSupport.transformOrOriginal(
                "查询翻译", original, () -> this.transformer.transform(original));
        log.info("TranslationQuery: {} -> {}", userText, translated.text());

        List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, translated);

        ChatClientRequest modified = augmentWhenPresent(request, original, docs);
        ChatClientResponse response = chain.nextCall(modified);
        response.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, docs);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();
        Query original = buildQuery(userText, request);
        Query translated = RagFallbackSupport.transformOrOriginal(
                "查询翻译", original, () -> this.transformer.transform(original));
        List<Document> docs = RagFallbackSupport.retrieveOrEmpty(retriever, translated);

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

    public TranslationQueryAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
