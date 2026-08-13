package com.njupt.wirelesslabagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * 权限校验 Advisor：检查 advisor context 中是否存在 userId，不存在则拒绝请求。
 * 使用方式：.advisors(spec -> spec.param("userId", currentUserId))
 */
@Slf4j
public class PermissionAdvisor implements BaseAdvisor {

    private int order = 0;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object userId = request.context().get("userId");

        if (userId == null || userId.toString().isBlank()) {
            log.debug("PermissionAdvisor: 未携带 userId，以访客身份放行");
            return request;
        }

        log.debug("PermissionAdvisor: userId={} 校验通过", userId);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    public PermissionAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
