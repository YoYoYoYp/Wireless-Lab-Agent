package com.njupt.wirelesslabagent.controller;

import com.njupt.wirelesslabagent.app.WirelessLabAgentApp;
import com.njupt.wirelesslabagent.common.BaseResponse;
import com.njupt.wirelesslabagent.common.ChatRequest;
import com.njupt.wirelesslabagent.common.ChatStopRequest;
import com.njupt.wirelesslabagent.common.ResuitUtils;
import com.njupt.wirelesslabagent.service.ChatStreamSessionManager;
import com.njupt.wirelesslabagent.service.ConversationHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
public class ChatController {

    private final WirelessLabAgentApp wirelessLabAgentApp;
    private final ConversationHistoryService conversationHistoryService;
    private final ChatStreamSessionManager streamSessionManager;

    public ChatController(WirelessLabAgentApp wirelessLabAgentApp,
                          ConversationHistoryService conversationHistoryService,
                          ChatStreamSessionManager streamSessionManager) {
        this.wirelessLabAgentApp = wirelessLabAgentApp;
        this.conversationHistoryService = conversationHistoryService;
        this.streamSessionManager = streamSessionManager;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        String chatId = StringUtils.hasText(request.chatId()) ? request.chatId() : "default";
        String streamId = StringUtils.hasText(request.streamId()) ? request.streamId() : UUID.randomUUID().toString();
        String userId = (String) httpRequest.getAttribute("currentUser");
        conversationHistoryService.track(userId, chatId, request.message());
        streamSessionManager.register(streamId, userId, chatId);
        return streamSessionManager.bind(streamId, wirelessLabAgentApp.doChat(request.message(), chatId, userId));
    }

    @PostMapping("/chat/stop")
    public BaseResponse<Boolean> stopChat(@Valid @RequestBody ChatStopRequest request, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("currentUser");
        return ResuitUtils.success(streamSessionManager.stop(request.streamId(), userId));
    }
}
