package com.njupt.wirelesslabagent.advisor;

import com.njupt.wirelesslabagent.service.ConversationSummaryService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/** 将 Redis 中的滚动摘要注入系统提示词，并传给后续查询压缩 Advisor。 */
public class ConversationSummaryAdvisor implements BaseAdvisor {

    public static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CONTEXT_KEY = "conversation_summary";

    private final ConversationSummaryService summaryService;
    private int order = -1;

    public ConversationSummaryAdvisor(ConversationSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object conversationId = request.context().get(CONVERSATION_ID_KEY);
        if (conversationId == null || conversationId.toString().isBlank()) {
            return request;
        }

        String summary = summaryService.buildContext(conversationId.toString());
        if (summary.isBlank()) {
            return request;
        }
        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage("\n【早期对话记忆】\n" + summary))
                .context(CONTEXT_KEY, summary)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    public ConversationSummaryAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
