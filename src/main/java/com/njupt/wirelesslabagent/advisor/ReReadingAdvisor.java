package com.njupt.wirelesslabagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

/**
 * RE2 (Re-Reading) Advisor：在发给 LLM 之前，把用户问题重复一遍，强制模型"再读一次题"。

 */
@Slf4j
public class ReReadingAdvisor implements BaseAdvisor {

    // 默认模板：原问题 + "再读一遍" + 原问题
    private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
            {re2_input_query}
            Read the question again: {re2_input_query}
            """;

    private final String re2AdviseTemplate;

    private int order = 2;

    // 无参构造 → 用默认模板
    public ReReadingAdvisor() {
        this(DEFAULT_RE2_ADVISE_TEMPLATE);
    }

    // 有参构造 → 自定义模板（占位符必须叫 {re2_input_query}）
    public ReReadingAdvisor(String re2AdviseTemplate) {
        this.re2AdviseTemplate = re2AdviseTemplate;
    }

    /**
     * 核心：在请求发给 LLM 之前，取出用户原文 → 用模板填空（复制一遍问题） → 塞回请求
     */
    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        // 1. 从原始请求中取出用户的纯文本问题
        String userText = chatClientRequest.prompt().getUserMessage().getText();

        // 2. 模板填空：把 {re2_input_query} 替换成用户的问题
        //    结果变成 "原问题\nRead the question again: 原问题"
        String augmentedUserText = new PromptTemplate(this.re2AdviseTemplate)
                .render(Map.of("re2_input_query", userText));

        log.info("ReReadingAdvisor: {}", augmentedUserText);

        // 3. 复制原请求，把用户消息替换成改造后的文本
        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
                .build();
    }

    // 输出侧不改任何东西，原样返回
    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    // 链式设置执行顺序（order 越小越先执行）
    public ReReadingAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }
}
