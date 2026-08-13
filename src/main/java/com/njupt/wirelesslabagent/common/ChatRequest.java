package com.njupt.wirelesslabagent.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "消息不能为空")
        @Size(max = 2000, message = "消息长度不能超过2000字")
        String message,

        @Size(max = 64, message = "会话ID过长")
        String chatId,

        @Size(max = 64, message = "streamId过长")
        String streamId
) {
}
