package com.njupt.wirelesslabagent.test;

import com.njupt.wirelesslabagent.app.WirelessLabAgentApp;
import com.njupt.wirelesslabagent.chatmemory.ConversationKeyFactory;
import com.njupt.wirelesslabagent.chatmemory.RedisChatMemoryRepository;
import com.njupt.wirelesslabagent.evaluation.RetrievalMetrics;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_RAG_EVALUATION", matches = "true")
class RagEvaluationTest {

    private static final int K = 3;
    private static final double THRESHOLD = 0.5;

    private static final String SDR = "sdr-basics.md";
    private static final String MODULATION = "modulation-link-design.md";
    private static final String USRP = "usrp-2943-guide.md";
    private static final String WORKFLOW = "wireless-experiment-workflows.md";
    private static final String CAPABILITY = "agent-sdr-capability-guide.md";
    private static final String TROUBLESHOOTING = "uhd-troubleshooting-guide.md";
    private static final String SAFETY = "rf-experiment-safety.md";

    /** 每道题标注到具体知识来源，而不是只标注宽泛类别。 */
    private static final List<RagCase> DATASET = List.of(
            c("软件无线电和传统无线电的核心区别是什么？", SDR),
            c("IQ 采样中的 I 和 Q 分别表示什么？", SDR),
            c("采样率与瞬时带宽有什么关系？", SDR),
            c("dBFS 和 dBm 能否直接换算？", SDR),
            c("底噪与信道干扰有什么区别？", SDR),
            c("寻找干净频点本质上在比较什么？", SDR),
            c("认知选频为什么要重复采样？", SDR),

            c("BPSK 为什么适合低信噪比链路？", MODULATION),
            c("QPSK 每个符号携带多少比特？", MODULATION),
            c("16-QAM 相比 QPSK 有什么优缺点？", MODULATION),
            c("2-FSK 为什么对相位误差不敏感？", MODULATION),
            c("自适应调制的基本思想是什么？", MODULATION),
            c("载波频偏会怎样影响相干解调？", MODULATION),
            c("符号率与采样率如何配合？", MODULATION),

            c("USRP-2943 的工作频率范围是多少？", USRP),
            c("USRP-2943 有几个发射和接收通道？", USRP),
            c("USRP-2943 的发射增益范围是多少？", USRP),
            c("USRP-2943 的接收增益范围是多少？", USRP),
            c("USRP-2943 最大 IQ 采样率是多少？", USRP),
            c("USRP-2943 的 ADC 和 DAC 分辨率是多少？", USRP),
            c("USRP-2943 需要多大输入电压？", USRP, SAFETY),

            c("频谱扫描默认使用什么中心频率？", WORKFLOW),
            c("扫频实验会不会主动发射信号？", WORKFLOW, SAFETY),
            c("Tone 回环默认基带频率是多少？", WORKFLOW),
            c("固定调制文本收发支持哪些调制方式？", WORKFLOW),
            c("文本收发没有提供文本内容时应该怎么办？", WORKFLOW),
            c("固定调制实验默认符号率是多少？", WORKFLOW),
            c("认知选频实验的执行顺序是什么？", WORKFLOW),

            c("设备状态查询应该走 RAG 还是工具？", CAPABILITY),
            c("实验原理问题应该走哪条处理链？", CAPABILITY),
            c("RIS 跨小组设备能力应该如何接入？", CAPABILITY),
            c("Agent_SDR 支持哪些文本收发调制方式？", CAPABILITY),
            c("哪些 Agent_SDR 工具会产生射频发射？", CAPABILITY, SAFETY),
            c("HTTP Bridge 与 MCP 通道有什么区别？", CAPABILITY),
            c("如何停止 Agent_SDR 当前硬件任务？", CAPABILITY),

            c("RX overflow 常见原因是什么？", TROUBLESHOOTING),
            c("TX underflow 应该如何排查？", TROUBLESHOOTING),
            c("出现 UHD not installed 应该检查什么？", TROUBLESHOOTING),
            c("RX timeout 与没有射频信号是一回事吗？", TROUBLESHOOTING),
            c("网络型 USRP 丢包应检查网卡和 MTU 吗？", TROUBLESHOOTING),
            c("设备被其他 GNU Radio 进程占用怎么办？", TROUBLESHOOTING),
            c("频谱能量很强但星座散乱应该怎么排查？", TROUBLESHOOTING, MODULATION),

            c("为什么 TX 和 RX 不能用无衰减同轴线直接连接？", SAFETY),
            c("20 dBm 发射降到 -15 dBm 至少需要多少衰减？", SAFETY),
            c("为什么衰减计算还要保留安全余量？", SAFETY),
            c("开始发射实验前需要检查哪些事项？", SAFETY),
            c("实验运行中出现哪些情况应该立即停止？", SAFETY),
            c("为什么接收端不能直接输入高功率信号？", SAFETY, USRP),
            c("向量库能否单独决定当前环境允许发射？", SAFETY)
    );
    private static final List<RagCase> GENERATION_CASES = List.of(
            DATASET.get(0), DATASET.get(7), DATASET.get(14), DATASET.get(21),
            DATASET.get(28), DATASET.get(35), DATASET.get(42), DATASET.get(48)
    );
    private static final List<String> OUT_OF_DOMAIN_QUERIES = List.of(
            "今天南京天气怎么样？", "帮我写一份 Java 简历", "推荐一家附近的餐厅",
            "如何学习高等数学？", "给我规划一次云南旅行", "股票明天会涨吗？",
            "红烧肉应该怎么做？", "世界杯下一场比赛是什么时候？"
    );

    @Autowired private VectorStore vectorStore;
    @Autowired private WirelessLabAgentApp agentApp;
    @Autowired private ChatModel chatModel;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RedisChatMemoryRepository memoryRepository;
    @Autowired private ConversationKeyFactory keyFactory;

    @Value("${rag.evaluation.min-hit-rate:0.85}") private double minHitRate;
    @Value("${rag.evaluation.min-precision:0.55}") private double minPrecision;
    @Value("${rag.evaluation.min-ndcg:0.70}") private double minNdcg;
    @Value("${rag.evaluation.min-generation-score:3.5}") private double minGenerationScore;
    @Value("${rag.evaluation.max-out-of-domain-hit-rate:0.25}") private double maxOutOfDomainHitRate;

    private final List<EvaluationResult> retrievalResults = new ArrayList<>();
    private final List<String> evaluationConversationIds = new ArrayList<>();
    private Set<String> availableSources;

    @BeforeAll
    void verifySdrKnowledgeIsReady() {
        availableSources = new HashSet<>(jdbcTemplate.queryForList(
                """
                SELECT DISTINCT metadata->>'source'
                FROM vector_store
                WHERE metadata->>'ingestion_origin' = 'bundled'
                  AND metadata->>'ingestion_complete' = 'true'
                  AND metadata->>'category' IN ('SDR基础','USRP设备','实验流程','故障诊断','安全规范')
                """,
                String.class));
        Set<String> required = Set.of(SDR, MODULATION, USRP, WORKFLOW, CAPABILITY, TROUBLESHOOTING, SAFETY);
        assertTrue(availableSources.containsAll(required),
                () -> "SDR 知识库未完成构建，缺少来源: " + difference(required, availableSources));
    }

    @AfterAll
    void cleanEvaluationMemory() {
        evaluationConversationIds.forEach(memoryRepository::deleteByConversationId);
    }

    @Test
    @Order(1)
    void evaluateRetrievalByLabeledSources() {
        retrievalResults.clear();
        for (RagCase testCase : DATASET) {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(testCase.question()).topK(K).similarityThreshold(THRESHOLD).build());
            List<String> sources = documents.stream().map(this::sourceOf).toList();
            RetrievalMetrics metrics = new RetrievalMetrics(
                    sources, testCase.expectedSources(), availableSources, K);
            retrievalResults.add(new EvaluationResult(testCase, documents, metrics));
        }

        double hitRate = retrievalResults.stream().filter(result -> result.metrics().hit()).count()
                / (double) retrievalResults.size();
        double recall = average(result -> result.metrics().recall());
        double precision = average(result -> result.metrics().precision());
        double map = average(result -> result.metrics().averagePrecision());
        double ndcg = average(result -> result.metrics().ndcg());
        double mrr = average(result -> result.metrics().reciprocalRank());

        log.info("RAG 检索评测: samples={}, topK={}, threshold={}", DATASET.size(), K, THRESHOLD);
        log.info("HitRate={}, Recall@{}={}, Precision@{}={}, MAP={}, NDCG@{}={}, MRR={}",
                fmt(hitRate), K, fmt(recall), K, fmt(precision), fmt(map), K, fmt(ndcg), fmt(mrr));

        assertTrue(hitRate >= minHitRate, () -> "HitRate=" + hitRate + "，低于阈值 " + minHitRate);
        assertTrue(precision >= minPrecision,
                () -> "Precision@" + K + "=" + precision + "，低于阈值 " + minPrecision);
        assertTrue(ndcg >= minNdcg, () -> "NDCG@" + K + "=" + ndcg + "，低于阈值 " + minNdcg);
    }

    @Test
    @Order(2)
    void evaluateOutOfDomainFalsePositiveRate() {
        long falseHits = OUT_OF_DOMAIN_QUERIES.stream().filter(query -> !vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(K).similarityThreshold(THRESHOLD).build()
        ).isEmpty()).count();
        double falsePositiveRate = (double) falseHits / OUT_OF_DOMAIN_QUERIES.size();
        log.info("域外问题误命中率: {}/{} = {}", falseHits, OUT_OF_DOMAIN_QUERIES.size(), falsePositiveRate);
        assertTrue(falsePositiveRate <= maxOutOfDomainHitRate,
                () -> "域外误命中率=" + falsePositiveRate + "，高于阈值 " + maxOutOfDomainHitRate);
    }

    @Test
    @Order(3)
    void evaluateGenerationUsingActuallyRetrievedContext() {
        ChatClient judge = ChatClient.builder(chatModel).build();
        List<GenerationScore> scores = new ArrayList<>();

        for (RagCase testCase : GENERATION_CASES) {
            String externalChatId = "rag-gen-" + UUID.randomUUID();
            String internalConversationId = keyFactory.scopedConversationId("rag-eval-user", externalChatId);
            evaluationConversationIds.add(internalConversationId);

            var result = agentApp.doChatSync(testCase.question(), externalChatId, "rag-eval-user");
            String actualContext = result.retrievedDocuments().stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
            assertFalse(actualContext.isBlank(), "生成评测必须使用实际检索到的上下文");

            String raw = judge.prompt().user(judgePrompt(testCase.question(), actualContext, result.answer()))
                    .call().content();
            scores.add(parseScores(raw));
        }

        double factual = scores.stream().mapToDouble(GenerationScore::factual).average().orElse(0);
        double complete = scores.stream().mapToDouble(GenerationScore::complete).average().orElse(0);
        double relevance = scores.stream().mapToDouble(GenerationScore::relevance).average().orElse(0);
        double citation = scores.stream().mapToDouble(GenerationScore::citation).average().orElse(0);
        log.info("生成质量(1-5): factual={}, complete={}, relevance={}, citation={}",
                factual, complete, relevance, citation);

        assertTrue(Math.min(Math.min(factual, complete), Math.min(relevance, citation)) >= minGenerationScore,
                () -> "生成质量存在低于阈值 " + minGenerationScore + " 的维度");
    }

    @Test
    @Order(4)
    void evaluateEndToEndLatency() {
        String question = "USRP-2943 的接收增益范围是多少？";
        runTimedQuery(question, "warmup");
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            durations.add(runTimedQuery(question, "run-" + i));
        }
        durations.sort(Comparator.naturalOrder());
        long p50 = percentile(durations, 0.50);
        long p95 = percentile(durations, 0.95);
        double average = durations.stream().mapToLong(Long::longValue).average().orElse(0);
        log.info("端到端延迟: avg={}ms, p50={}ms, p95={}ms, samples={}", average, p50, p95, durations);
    }

    private long runTimedQuery(String question, String suffix) {
        String externalChatId = "rag-latency-" + suffix + "-" + UUID.randomUUID();
        String internalConversationId = keyFactory.scopedConversationId("rag-eval-user", externalChatId);
        evaluationConversationIds.add(internalConversationId);
        long start = System.nanoTime();
        agentApp.doChatSync(question, externalChatId, "rag-eval-user");
        return (System.nanoTime() - start) / 1_000_000;
    }

    private double average(java.util.function.ToDoubleFunction<EvaluationResult> function) {
        return retrievalResults.stream().mapToDouble(function).average().orElse(0);
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private String sourceOf(Document document) {
        return String.valueOf(document.getMetadata().getOrDefault("source", "unknown"));
    }

    private String judgePrompt(String question, String context, String answer) {
        String limitedContext = context.length() > 4_000 ? context.substring(0, 4_000) : context;
        return """
                根据给定的实际检索上下文，对回答进行 1-5 分评分，不得使用上下文之外的知识。
                问题：%s
                实际检索上下文：%s
                回答：%s
                只输出四行：
                事实准确性: X
                答案完整性: X
                上下文相关性: X
                引用准确性: X
                """.formatted(question, limitedContext, answer);
    }

    private GenerationScore parseScores(String raw) {
        return new GenerationScore(
                score(raw, "事实准确性"), score(raw, "答案完整性"),
                score(raw, "上下文相关性"), score(raw, "引用准确性"));
    }

    private double score(String raw, String name) {
        for (String line : raw.split("\\R")) {
            if (line.contains(name)) {
                try {
                    return Double.parseDouble(line.replaceAll("[^0-9.]", ""));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static RagCase c(String question, String... sources) {
        return new RagCase(question, Set.of(sources));
    }

    private static Set<String> difference(Set<String> required, Set<String> actual) {
        Set<String> difference = new HashSet<>(required);
        difference.removeAll(actual);
        return difference;
    }

    private String fmt(double value) {
        return String.format("%.3f", value);
    }

    private record RagCase(String question, Set<String> expectedSources) {
    }

    private record EvaluationResult(RagCase testCase,
                                    List<Document> documents,
                                    RetrievalMetrics metrics) {
    }

    private record GenerationScore(double factual,
                                   double complete,
                                   double relevance,
                                   double citation) {
    }
}
