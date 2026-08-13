package com.njupt.wirelesslabagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DashScope Rerank 配置：提供专用精排模型，独立于向量库类型。
 * <p>DashScope Rerank API 是专门为文档重排序设计的接口，
 * 相比 ChatClient 通用 LLM 调用，延迟更低、成本更低。
 */
@Configuration
public class DashScopeRerankConfig {

    @Bean
    public RerankModel rerankModel(@Value("${spring.ai.dashscope.api-key}") String apiKey) {
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        DashScopeRerankOptions options = DashScopeRerankOptions.builder()
                .topN(3)
                .returnDocuments(true)
                .build();
        return new DashScopeRerankModel(api, options);
    }
}
