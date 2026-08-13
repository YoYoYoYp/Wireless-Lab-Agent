# RAG 评估体系详解

> 面试常问：RAG 实现不难，怎么证明做得好？怎么测试？
> 项目代码：`RagEvaluationTest.java`、`PgVectorRetrievalTest.java`、`RagTest.java`

---

## 1. 为什么需要分层评估

如果只看最终答案好不好，你不知道是"检索没找到"还是"LLM 没用好找到的文档"。分层能**定位问题到底出在哪个环节**。

```
┌────────────────────────────────────────────────────────┐
│                 RAG 评估金字塔                          │
├────────────────────────────────────────────────────────┤
│  ③ 端到端延迟（用户体验）     ← System.currentTimeMs   │
├────────────────────────────────────────────────────────┤
│  ② 生成质量（LLM-as-Judge）   ← LLM 对答案打分 1-5     │
├────────────────────────────────────────────────────────┤
│  ① 检索质量（离线指标）       ← Recall/Precision/MAP/NDCG │
└────────────────────────────────────────────────────────┘
```

**三层分别回答三个问题**：
- 检索质量：文档找对了吗？
- 生成质量：基于找到的文档，答案好吗？
- 端到端延迟：用户体验快吗？

---

## 2. 标注数据集

项目用 `category` 做弱标注——问题属于某个分类，检索到的文档也应是该分类：

```java
// RagEvaluationTest.java:34-42
private static final List<TestCase> DATASET = List.of(
    new TestCase("HashMap 底层实现原理是什么？", Set.of("技术面")),
    new TestCase("请用 STAR 法则介绍一个项目", Set.of("行为面")),
    new TestCase("简历中项目经历应该怎么写？", Set.of("简历")),
    new TestCase("ConcurrentHashMap 如何保证线程安全？", Set.of("技术面")),
    new TestCase("面试中如何回答技术分歧类问题？", Set.of("行为面")),
    new TestCase("简历投递有什么策略？", Set.of("简历")),
    new TestCase("Synchronized 和 ReentrantLock 的区别？", Set.of("技术面"))
);
```

**为什么用弱标注而不是逐条标注"相关/不相关"？** 逐条标注成本极高——知识库 15 个文档，每个问题需要人工逐条判断。弱标注用文档分类做代理指标，在小规模评估中足够发现方向性问题。工业化做法才是逐条标注。

---

## 3. 第一层：检索质量 — 四个核心指标

代码在 `RagEvaluationTest.java:69-111`。四个指标分两个维度：

### 3.1 覆盖率维度：Recall@K

**"该找回来的，找回来了多少？"**

```java
// RagEvaluationTest.java:309-313
double recall() {
    if (totalRelevantInKB == 0) return 0;
    long relevantRetrieved = countRelevant();
    return (double) relevantRetrieved / totalRelevantInKB;
}
```

```
Recall@5 = 检索到的相关文档数 / 知识库中所有相关文档数

例：问"HashMap 底层原理"，知识库有 5 篇技术面文档
    检索返回 5 条，其中 3 条是技术面 → Recall = 3/5 = 0.6
```

**关键细节**：`totalRelevantInKB` 通过 `countRelevantInKB()` 预先统计：

```java
// RagEvaluationTest.java:222-229
private long countRelevantInKB(Set<String> categories) {
    return vectorStore.similaritySearch(
            SearchRequest.builder().query("").topK(100).similarityThreshold(0.0).build())
            .stream()
            .filter(d -> categories.contains(
                    String.valueOf(d.getMetadata().getOrDefault("category", "未知"))))
            .count();
}
```

用 `topK=100, threshold=0.0` 扫全库，统计带目标 category 的文档总数。

### 3.2 精度维度：Precision@K

**"找回来的，有多少是有效的？"**

```java
// RagEvaluationTest.java:316-319
double precision() {
    if (docs.isEmpty()) return 0;
    return (double) countRelevant() / docs.size();
}
```

```
Precision@5 = 检索到的相关文档数 / 检索到的总文档数

例：返回 5 条，其中 3 条相关 → Precision = 3/5 = 0.6
```

**Recall 和 Precision 互相制约**：阈值太低 → Recall 高但 Precision 低（召回一堆噪声），阈值太高 → Precision 高但 Recall 低（漏掉相关文档）。所以需要综合看。

### 3.3 排序维度：MAP（Mean Average Precision）

**"相关文档排得靠前吗？"**

```java
// RagEvaluationTest.java:325-334
double averagePrecision() {
    int relevantSoFar = 0;
    double sum = 0;
    for (int i = 0; i < docs.size(); i++) {
        if (isRelevant(docs.get(i))) {
            relevantSoFar++;
            sum += (double) relevantSoFar / (i + 1);  // 在位置 i+1 的 precision
        }
    }
    return totalRelevantInKB == 0 ? 0 : sum / totalRelevantInKB;
}
```

```
假设 top 5 结果：[相关, 不相关, 相关, 相关, 不相关]，共 4 篇相关

位置 1: 相关 → P@1 = 1/1 = 1.0
位置 2: 不相关 → 不算
位置 3: 相关 → P@3 = 2/3 = 0.67
位置 4: 相关 → P@4 = 3/4 = 0.75

AP = (1.0 + 0.67 + 0.75) / 4 = 0.605
```

Precision 只看"前 K 条里有多少相关"，不关心相关文档排在第几位。MAP 对排序敏感——相关文档排得越靠前，分数越高。

### 3.4 排序维度：NDCG@K（Normalized Discounted Cumulative Gain）

**"好的文档排在前面了吗？（考虑相关程度）"**

```java
// RagEvaluationTest.java:343-363
double ndcg() {
    List<Double> gains = docs.stream()
        .map(d -> isRelevant(d) ? 2.0 : 0.0)  // 相关=2，不相关=0
        .toList();

    // DCG: 每个位置的 gain 除以位置的对数（位置靠后权重衰减）
    double dcg = 0;
    for (int i = 0; i < gains.size(); i++) {
        dcg += (Math.pow(2, gains.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
    }

    // IDCG: 理想排序下的 DCG（所有相关文档排最前面）
    List<Double> ideal = gains.stream().sorted(Comparator.reverseOrder()).toList();
    double idcg = 0;
    for (int i = 0; i < ideal.size(); i++) {
        idcg += (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
    }

    return idcg == 0 ? 0 : dcg / idcg;
}
```

**NDCG 和 MAP 的区别**：
- MAP 只关心"相关/不相关"（二值）
- NDCG 支持"相关程度"（如 0=不相关, 1=部分相关, 2=高度相关），项目用二值但保留了扩展性

### 3.5 补充指标：Hit Rate

```java
// RagEvaluationTest.java:210-211
long hits = revals.stream().filter(r -> r.recall() > 0).count();
log.info("  Hit Rate:     {}/{}", hits, revals.size());
```

**"至少命中一条的查询占多少？"** 对用户体验来说，"一条都搜不到"和"搜到两条但排序靠后"是完全不同的问题。Hit Rate 专门衡量这个。

### 3.6 指标对比总结

| 指标 | 衡量什么 | 对排序敏感 | 支持相关程度 | 面试分值 |
|------|---------|-----------|------------|---------|
| Recall@K | 覆盖率 | 否 | 否 | ⭐⭐⭐ |
| Precision@K | 准确率 | 否 | 否 | ⭐⭐⭐ |
| MAP | 排序质量 | **是** | 否 | ⭐⭐ |
| NDCG@K | 排序质量+相关程度 | **是** | **是** | ⭐⭐ |
| Hit Rate | 可用性 | 否 | 否 | ⭐ |

---

## 4. 第二层：生成质量 — LLM-as-Judge

代码在 `RagEvaluationTest.java:116-161`。核心思想：**用一个功能更强的 LLM 评判 RAG 输出的答案质量**。

### 4.1 评估 Prompt

```java
// RagEvaluationTest.java:256-280
private String buildJudgePrompt(String question, String context, String answer) {
    return """
            你是 RAG 系统评估专家。对 AI 回答评分 (1-5 分)。
            【问题】%s
            【检索上下文】%s
            【AI 回答】%s
            严格按以下格式输出（每行一个数字，不要解释）：
            事实准确性: X
            答案完整性: X
            上下文相关性: X
            引用准确性: X
            """.formatted(question, context, answer);
}
```

### 4.2 四个评估维度

| 维度 | 低分表现 | 问题定位 |
|------|---------|---------|
| 事实准确性 | 模型在编造信息（幻觉） | 检索召回不足 或 LLM 幻觉倾向 |
| 答案完整性 | 只答了问题的一部分 | Prompt 模板 或 检索 threshold 问题 |
| 上下文相关性 | 答非所问，偏离主题 | 检索噪声 或 Prompt 引导问题 |
| 引用准确性 | 引用了文档里没有的内容 | 严重的幻觉问题 |

### 4.3 为什么 LLM-as-Judge 是可行的

传统方法需要人工逐条打分，成本高、不可复现。LLM-as-Judge 的优势：
- **效率**：7 条用例 × 4 个维度 = 28 个打分项，LLM 秒级完成
- **可复现**：固定 Prompt + 固定 Temperature=0，每次评估结果一致
- **可扩展**：新增测试用例只需加一行 TestCase，零人工成本

**局限性**：LLM 自身也有偏见——可能给长答案高分、对某些领域判断不准。所以项目把 Judge 的输出格式约束为纯数字，减少自由发挥的偏差。

---

## 5. 第三层：端到端延迟

```java
// RagEvaluationTest.java:166-186
@Test
void evaluateLatency() {
    String q = "HashMap 底层实现原理是什么？";
    // warmup: 第一次慢（建立连接、JIT 预热）
    interviewApp.doChatSync(q, "lat-warm", "perf-test");

    List<Long> times = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
        long start = System.currentTimeMillis();
        interviewApp.doChatSync(q, "lat-" + i, "perf-test");
        times.add(System.currentTimeMillis() - start);
    }
    double avg = times.stream().mapToLong(Long::longValue).average().orElse(0);
}
```

**延迟的构成**（自动分类 RAG 路径）：

```
用户消息
  → classifyQuery()       ← 一次 LLM 调用（轻量分类）
  → 策略路由
  → 检索（PGVector）       ← 向量相似度搜索
  → LLM 生成（流式）       ← 主要耗时
  → 返回第一个 token → ... → 完整回答
```

**面试加分点**：
- 加了 warmup，排除冷启动的连接建立 + JIT 编译开销
- 3 次取平均，不是单次测量
- 后续可做：并发压测（pgvector HNSW 索引 + DashScope API 的吞吐瓶颈）、P50/P95/P99 百分位延迟

---

## 6. 辅助测试：命中/未命中验证

`PgVectorRetrievalTest.java` 提供了"能不能搜到"的快速验证：

```java
// 命中测试：相关问题应返回结果
String[] testQueries = {
    "怎么提升自身魅力吸引女生？",
    "恋爱中吵架了怎么办？"
};

// 未命中测试：无关问题应返回空
String[] unrelatedQueries = {
    "今天天气怎么样？",
    "帮我写一段 Python 代码"
};
```

**为什么需要两个方向**：只测命中（正向）不够——如果"今天天气怎么样"也返回文档，说明相似度阈值太低，噪声太多。正反两向验证阈值设置是否合理。

---

## 7. 综合评估报告

```java
// RagEvaluationTest.java:192-215
@Test
@Order(4)
@DisplayName("4. 综合评估报告")
void summary() {
    // 输出一张包含所有检索指标 + Hit Rate 的综合报告
}
```

**输出示例**：

```
═══════════════════════════════════════════════════════
       RAG 系统综合评估报告
═══════════════════════════════════════════════════════
【检索质量】  topK=5  threshold=0.3
  Recall@5:    0.71
  Precision@5: 0.54
  MAP:         0.62
  NDCG@5:      0.68
  Hit Rate:    7/7
═══════════════════════════════════════════════════════
```

---

## 8. 面试回答模板

### 面试官："RAG 怎么测试？怎么证明效果好？"

> "我设计了三层评估体系。第一层检索质量，用标注数据集测 Recall、Precision、MAP、NDCG 四个指标——Recall 看召回覆盖率，Precision 看准确率，MAP 和 NDCG 加了排序维度。第二层生成质量，用 LLM-as-Judge 从事实准确性、完整性、上下文相关性、引用准确性四个角度打分，这比人工评估高效且可复现。第三层端到端延迟，包含自动分类+检索+生成的完整耗时。三层分别回答'文档找对了吗''答案好吗''用户快吗'三个问题。"

### 面试官追问："标注数据怎么来？"

> "当前用弱标注——问题属于某个分类，检索到的文档也应是该分类。这比逐条标注'相关/不相关'成本低得多，小规模评估足够发现方向性问题。工业化做法是逐条标注，或者用用户点击/停留时长做隐式反馈。另外我还做了命中/未命中的正反验证——既测'该搜到的搜到了吗'，也测'不该搜到的是否被过滤了'——验证相似度阈值设置是否合理。"

### 面试官追问："LLM-as-Judge 可靠吗？"

> "LLM-as-Judge 有自身偏见——可能给长答案高分、某些领域判断不准。我的做法是：固定 Prompt 模板 + Temperature=0 保证可复现，约束输出格式为纯数字减少自由发挥偏差，四个维度互相校验（引用准确性低但事实准确性高 → 可能是检索够了但 LLM 自由发挥了）。最终需要人工抽查验证 Judge 的一致率。"
