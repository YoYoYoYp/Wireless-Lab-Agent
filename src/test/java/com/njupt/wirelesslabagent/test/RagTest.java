package com.njupt.wirelesslabagent.test;

import com.njupt.wirelesslabagent.app.WirelessLabAgentApp;
import com.njupt.wirelesslabagent.app.WirelessLabAgentApp.RagChatResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

@Slf4j
public class RagTest {

    @Resource
    private WirelessLabAgentApp wirelessLabAgentApp;

    public void testRag() {
        log.info("========== RAG 功能验证 ==========");

        String timeSuffix = String.valueOf(System.currentTimeMillis());

        testRagHit("USRP-2943 的接收增益范围是多少？", "rag-hit-" + timeSuffix);
        testRagMiss("今天天气怎么样？", "rag-miss-" + timeSuffix);

        log.info("========== RAG 验证完成 ==========");
    }

    private void testRagHit(String question, String chatId) {
        log.info("【命中测试】提问：{}", question);
        RagChatResult result = wirelessLabAgentApp.doChatSync(question, chatId, "test-user");

        log.info("  命中文档数: {}", result.retrievedDocuments().size());

        for (Document doc : result.retrievedDocuments()) {
            log.info("    [{}] {} (keywords: {}, score: {})",
                    doc.getMetadata().get("category"),
                    doc.getMetadata().get("title"),
                    doc.getMetadata().getOrDefault("excerpt_keywords", "无"),
                    String.format("%.2f", doc.getScore()));
        }

        if (result.hasDocuments()) {
            log.info("  ✅ RAG 生效：检索到 {} 条文档", result.retrievedDocuments().size());
        } else {
            log.warn("  ⚠️ 未检索到文档，相似度可能低于阈值");
        }
        log.info("  回答: {}", result.answer());
    }

    private void testRagMiss(String question, String chatId) {
        log.info("【未命中测试】提问：{}", question);
        RagChatResult result = wirelessLabAgentApp.doChatSync(question, chatId, "test-user");

        log.info("  命中文档数: {}", result.retrievedDocuments().size());

        if (!result.hasDocuments()) {
            log.info("  ✅ 符合预期：无关问题未命中知识库");
        } else {
            log.warn("  ⚠️ 意外命中 {} 条文档", result.retrievedDocuments().size());
        }
        log.info("  回答: {}", result.answer());
    }
}
