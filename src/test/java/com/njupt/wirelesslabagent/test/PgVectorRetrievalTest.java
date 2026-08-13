package com.njupt.wirelesslabagent.test;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
@Slf4j
@EnabledIfEnvironmentVariable(named = "RUN_RAG_EVALUATION", matches = "true")
class PgVectorRetrievalTest {

    @Autowired
    private VectorStore vectorStore;

    @Test
    @DisplayName("RAG命中测试 — 检索知识库 + 查看相似度得分")
    void testRetrievalHit() {
        log.info("========== PGVector 检索验证 ==========");
        log.info("VectorStore 类型: {}", vectorStore.getClass().getSimpleName());

        // 先检查知识库有没有数据（随便搜一个词探测）
        List<Document> probe = vectorStore.similaritySearch(
                SearchRequest.builder().query("test").topK(1).similarityThreshold(0.0).build());
        log.info("知识库文档总数探测: topK=1 返回 {} 条 (0表示知识库为空)", probe.size());

        // 正式检索：与知识库高度相关的提问
        String[] testQueries = {
                "USRP-2943 的工作频率范围是多少？",
                "BPSK 为什么适合低信噪比链路？",
                "RX overflow 应该如何排查？",
                "射频回环为什么必须使用衰减器？"
        };

        for (String query : testQueries) {
            log.info("--------------------------------------------------");
            log.info("【检索问题】{}", query);

            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(3)
                            .similarityThreshold(0.3)
                            .build());

            if (docs.isEmpty()) {
                log.warn("  ⚠️ 未检索到任何文档 (相似度可能 < 0.3)");
            } else {
                log.info("  命中 {} 条文档:", docs.size());
                for (int i = 0; i < docs.size(); i++) {
                    Document doc = docs.get(i);
                    String category = doc.getMetadata() != null
                            ? String.valueOf(doc.getMetadata().getOrDefault("category", "未知"))
                            : "未知";
                    String title = doc.getMetadata() != null
                            ? String.valueOf(doc.getMetadata().getOrDefault("title", "无标题"))
                            : "无标题";
                    String keywords = doc.getMetadata() != null
                            ? String.valueOf(doc.getMetadata().getOrDefault("excerpt_keywords", "无"))
                            : "无";
                    log.info("    [{}] category={} | title={} | keywords={} | score={}",
                            i + 1,
                            category,
                            title,
                            keywords,
                            doc.getScore() != null
                                    ? String.format("%.4f", doc.getScore())
                                    : "N/A (无得分)");
                    // 打印文档前 100 字
                    String preview = doc.getText() != null
                            ? doc.getText().substring(0, Math.min(100, doc.getText().length()))
                            : "";
                    log.info("      content preview: {}...", preview.replace("\n", " "));
                }
            }
        }

        log.info("========== 检索验证完成 ==========");
    }

    @Test
    @DisplayName("RAG未命中测试 — 无关问题应返回空")
    void testRetrievalMiss() {
        log.info("========== PGVector 未命中验证 ==========");

        String[] unrelatedQueries = {
                "今天天气怎么样？",
                "帮我写一段 Python 代码",
                "推荐一家餐厅"
        };

        for (String query : unrelatedQueries) {
            log.info("【无关问题】{}", query);
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(3)
                            .similarityThreshold(0.5)
                            .build());

            if (docs.isEmpty()) {
                log.info("  ✅ 符合预期：未命中 (相似度 < 0.5)");
            } else {
                log.warn("  ⚠️ 意外命中 {} 条", docs.size());
                for (Document doc : docs) {
                    log.info("    title={} score={}",
                            doc.getMetadata().get("title"),
                            String.format("%.4f", doc.getScore()));
                }
            }
        }

        log.info("========== 未命中验证完成 ==========");
    }
}
