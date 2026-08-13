package com.njupt.wirelesslabagent.config;

import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "spring.ai.vector-store.type", havingValue = "pgvector")
public class PgVectorConfig {

    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("vector_store")
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                /*
                 * 批处理策略：将文档按 token 数拆分为小批次，逐批调用 Embedding API。
                 * 避免单次请求超模型 token 上限导致报错，同时控制 API 速率压力。
                 * 当前项目文档量小（~15 条），批处理基本不触发。
                 * 扩容到百/千级文档时，自动按 500 token/批 拆分，无需改代码。
                 */
                .batchingStrategy(new TokenCountBatchingStrategy(
                        EncodingType.CL100K_BASE,
                        800,   // TokenTextSplitter 500 → 留余量
                        0.1
                ))
                .build();
    }
}
