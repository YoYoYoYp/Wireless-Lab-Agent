package com.njupt.wirelesslabagent.chatmemory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将外部 chatId 转为带用户作用域的内部 conversationId。
 * 长度前缀可避免 userId/chatId 自身包含冒号时发生拼接碰撞。
 */
@Component
public class ConversationKeyFactory {

    public String scopedConversationId(String userId, String chatId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId 不能为空");
        }
        String normalizedUserId = userId.trim();
        String normalizedChatId = chatId.trim();
        return normalizedUserId.length() + ":" + normalizedUserId + ":" + normalizedChatId;
    }
}
