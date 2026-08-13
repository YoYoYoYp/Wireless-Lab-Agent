package com.njupt.wirelesslabagent.controller;

import com.njupt.wirelesslabagent.chatmemory.ConversationKeyFactory;
import com.njupt.wirelesslabagent.chatmemory.RedisChatMemoryRepository;
import com.njupt.wirelesslabagent.common.BaseResponse;
import com.njupt.wirelesslabagent.common.ResuitUtils;
import com.njupt.wirelesslabagent.service.ConversationHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/conversations")
public class ChatHistoryController {

    private final ConversationHistoryService historyService;
    private final ConversationKeyFactory keyFactory;
    private final RedisChatMemoryRepository memoryRepository;

    public ChatHistoryController(ConversationHistoryService historyService,
                                 ConversationKeyFactory keyFactory,
                                 RedisChatMemoryRepository memoryRepository) {
        this.historyService = historyService;
        this.keyFactory = keyFactory;
        this.memoryRepository = memoryRepository;
    }

    /**
     * 获取当前用户的会话列表（标题 = 第一条用户消息）
     */
    @GetMapping
    public BaseResponse<List<Map<String, Object>>> listConversations(HttpServletRequest request) {
        String username = (String) request.getAttribute("currentUser");
        List<Map<String, Object>> result = historyService.list(username).stream().map(entry -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chatId", entry.chatId());
            item.put("title", entry.title());
            return item;
        }).toList();
        return ResuitUtils.success(result);
    }

    /**
     * 获取某会话全部消息
     */
    @GetMapping("/{chatId}")
    public BaseResponse<List<Map<String, String>>> getMessages(@PathVariable String chatId, HttpServletRequest request) {
        String username = (String) request.getAttribute("currentUser");
        // 验证该会话属于当前用户
        if (!historyService.isOwnedBy(username, chatId)) {
            return ResuitUtils.success(List.of());
        }

        String scopedConversationId = keyFactory.scopedConversationId(username, chatId);
        var messages = memoryRepository.findByConversationId(scopedConversationId);
        if (messages.isEmpty()) {
            return ResuitUtils.success(List.of());
        }

        historyService.touch(username, chatId);
        List<Map<String, String>> result = messages.stream()
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
                .map(message -> Map.of(
                        "role", message.getMessageType() == MessageType.USER ? "user" : "assistant",
                        "content", message.getText()
                ))
                .toList();
        return ResuitUtils.success(result);
    }
}
