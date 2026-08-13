package com.njupt.wirelesslabagent.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatStopRequest(
        @NotBlank(message = "streamId不能为空")
        @Size(max = 64, message = "streamId过长")
        String streamId
) {
}
