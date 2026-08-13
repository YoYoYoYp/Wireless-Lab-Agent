package com.njupt.wirelesslabagent.app;

import com.njupt.wirelesslabagent.common.RagStrategy;
import com.njupt.wirelesslabagent.config.ChatClientFactory;
import com.njupt.wirelesslabagent.chatmemory.ConversationKeyFactory;
import com.njupt.wirelesslabagent.service.QueryRoutingService;
import com.njupt.wirelesslabagent.tools.SdrHardwareTool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class WirelessLabAgentApp {

    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private final Map<RagStrategy, ChatClient> clients;
    private final String systemPrompt;
    private final SdrHardwareTool sdrHardwareTool;
    private final ToolCallbackProvider mcpTools;
    private final ConversationKeyFactory conversationKeyFactory;
    private final QueryRoutingService queryRoutingService;

    public WirelessLabAgentApp(ChatClientFactory factory,
                               ConversationKeyFactory conversationKeyFactory,
                               QueryRoutingService queryRoutingService,
                               SdrHardwareTool sdrHardwareTool,
                               ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
                               @Value("classpath:/prompts/wireless-lab-system-prompt.st") Resource systemResource) {
        this.systemPrompt = loadSystemPrompt(systemResource);
        this.clients = factory.buildAll(systemPrompt);
        this.conversationKeyFactory = conversationKeyFactory;
        this.queryRoutingService = queryRoutingService;
        this.sdrHardwareTool = sdrHardwareTool;
        this.mcpTools = toolCallbackProvider.getIfAvailable();
    }

    @PostConstruct
    public void logTools() {
        if (mcpTools == null) {
            log.info("Agent_SDR MCP 未启用，硬件请求使用 HTTP Agent Bridge");
            return;
        }
        ToolCallback[] callbacks = mcpTools.getToolCallbacks();
        log.info("已加载 {} 个 MCP 工具", callbacks.length);
        for (ToolCallback callback : callbacks) {
            log.info("MCP tool: {}", callback.getToolDefinition().name());
        }
    }

    public Flux<String> doChat(String message, String chatId, String userId) {
        var decision = queryRoutingService.route(message);
        RagStrategy strategy = decision.strategy();
        String scopedConversationId = conversationKeyFactory.scopedConversationId(userId, chatId);
        log.info("[lab-route] {} -> {} ({}, fallback={})",
                message, strategy, decision.label(), decision.fallback());

        var prompt = clients.get(strategy)
                .prompt()
                .user(message)
                .advisors(spec -> spec
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, scopedConversationId)
                        .param("userId", userId));

        if (mcpTools == null) {
            return prompt.tools(sdrHardwareTool).stream().content();
        }
        return prompt.tools(sdrHardwareTool)
                .toolCallbacks(mcpTools)
                .stream()
                .content();
    }

    public RagChatResult doChatSync(String message, String chatId, String userId) {
        RagStrategy strategy = queryRoutingService.route(message).strategy();
        String scopedConversationId = conversationKeyFactory.scopedConversationId(userId, chatId);
        ChatClientResponse response = clients.get(strategy)
                .prompt()
                .user(message)
                .advisors(spec -> spec
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, scopedConversationId)
                        .param("userId", userId))
                .call()
                .chatClientResponse();

        @SuppressWarnings("unchecked")
        List<Document> retrievedDocs = (List<Document>) response.context()
                .getOrDefault(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of());
        return new RagChatResult(response.chatResponse().getResult().getOutput().getText(), retrievedDocs);
    }

    public record RagChatResult(String answer, List<Document> retrievedDocuments) {
        public boolean hasDocuments() {
            return retrievedDocuments != null && !retrievedDocuments.isEmpty();
        }
    }

    private String loadSystemPrompt(Resource resource) {
        return new SystemPromptTemplate(resource).render(Map.of("name", "无线实验室设备控制与知识智能体"));
    }
}
