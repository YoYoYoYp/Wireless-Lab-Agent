package com.njupt.wirelesslabagent.advisor;

import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

/**
 * DashScope 专用精排器：通过 {@link RerankModel} 对粗检文档重排序。
 * <p>DashScope Rerank API 是专门为文档相关性打分设计的接口，
 * 单次调用 < 100ms，远快于 ChatClient 通用 LLM（~500ms）。
 *
 * <p>两步 RAG 完整流程：
 * <pre>
 *   粗排（Retrieval）：VectorStoreDocumentRetriever 低阈值多召回 → N 条候选
 *   精排（Post-Retrieval）：本类 RerankModel 专用打分 → 排序 → 截断 topN
 * </pre>
 */
@Slf4j
public class RerankingDocumentPostProcessor implements DocumentPostProcessor {

    private final RerankModel rerankModel;
    private final int topN;

    public RerankingDocumentPostProcessor(RerankModel rerankModel, int topN) {
        this.rerankModel = rerankModel;
        this.topN = topN;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.size() <= topN) {
            log.info("Reranking: 候选文档数 {} <= topN {}，跳过", documents.size(), topN);
            return documents;
        }

        log.info("Reranking: {} 条候选 → DashScope Rerank API → 截断 topN={}", documents.size(), topN);

        RerankRequest request = new RerankRequest(query.text(), documents);
        RerankResponse response = rerankModel.call(request);

        List<Document> reranked = response.getResults().stream()
                .peek(ds -> log.debug("  score={} title={}",
                        ds.getScore(), ds.getOutput().getMetadata().get("title")))
                .map(com.alibaba.cloud.ai.document.DocumentWithScore::getOutput)
                .limit(topN)
                .toList();


        log.info("Reranking: 精排完成，返回 {} 条", reranked.size());
        return reranked;
    }
}
