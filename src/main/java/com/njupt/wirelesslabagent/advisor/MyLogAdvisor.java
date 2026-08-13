package com.njupt.wirelesslabagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
@Slf4j
public class MyLogAdvisor implements CallAdvisor,StreamAdvisor {

    private int order = 0;

    public MyLogAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }

    /**
     * 自定义非流式拦截器，在调用之前和调用之后打印日志
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        log.info("MyLogAdvisor: {}", request);
        // 调用下一个拦截器（LLM）
        ChatClientResponse response = chain.nextCall(request);
        // 打印工具调用明细
        var assistantMsg = response.chatResponse().getResult().getOutput();
        if (!assistantMsg.getToolCalls().isEmpty()) {
            assistantMsg.getToolCalls().forEach(tc ->
                log.info(">>> [工具调用] {} 参数: {}", tc.name(), tc.arguments())
            );
        }
        log.info("MyLogAdvisor: {}", response);
        return response;
    }
    /**
     * 自定义流式拦截器，在调用之前和调用之后打印日志
     */

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        log.info("MyLogAdvisor: {}", request);
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        //
        Flux<ChatClientResponse> chatClientResponseFlux = new ChatClientMessageAggregator().aggregateChatClientResponse(flux, resp -> {
            log.info("MyLogAdvisor: {}", resp);//聚合后的结果，resp 此时已经是完整响应
        });
        return chatClientResponseFlux;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return this.order;
    }
}
