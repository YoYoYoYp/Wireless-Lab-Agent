# Spring AI RAG 模块化架构笔记

> 覆盖 4 大模块：Pre-Retrieval → Retrieval → Post-Retrieval → Generation
> 项目实战：`CompressionQueryAdvisor` `RewriteQueryAdvisor` `TranslationQueryAdvisor` `MultiQueryExpansionAdvisor`
> + `VectorStoreDocumentRetriever` + `ConcatenationDocumentJoiner` + `ContextualQueryAugmenter`
> 官方文档：spring-ai/reference/api/rag.html

---

## 1. 为什么需要预检索优化？

### 1.1 问题场景

`QuestionAnswerAdvisor` 的检索策略是"用户输入什么，就用什么去向量库搜"：

```
用户："那这个小区呢？"
  → vectorStore.similaritySearch("那这个小区呢？")
  → 召回：空（向量库中没有"那这个小区"相关文档）
  → LLM：我不知道

// 实际上前一轮对话刚问过"碧海湾小区在哪"，用户是在追问
```

三个典型问题：

| 问题 | 根因 | 对应技术 |
|------|------|---------|
| 检索范围太窄，漏掉相关文档 | 只搜1次，单一措辞 | **多查询扩展（MultiQueryExpander）** |
| 用户表述模糊/口语化 | 口语 ≠ 文档中的正式表述 | **查询重写（RewriteQueryTransformer）** |
| 用户语言 ≠ 知识库语言 | 中文问题搜不了英文知识库 | **查询翻译（TranslationQueryTransformer）** |
| 代词/省略依赖上文 | "它""那个"指代不明 | **上下文感知查询（Query.history）** |

### 1.2 在 RAG 流程中的位置

```
Pre-Retrieval（预检索）    →  Retrieval（检索）    →  Post-Retrieval →  Generation
   ↑ 本文聚焦这一阶段             ↑                      ↑                 ↑
   查询扩展/重写/翻译           VectorStore.search()    文档后处理         LLM 回答
```

类比：你去图书馆查资料之前，先整理一下自己的问题——把模糊的话说清楚（重写）、用不同的关键词多查几次（扩展）、翻译成图书馆用的语言（翻译）。这些"整理"步骤就是预检索优化。

---

## 2. 三个核心 API 对比

Spring AI `spring-ai-rag` 模块提供了三个预检索组件，都依赖 `ChatClient.Builder`（用 LLM 做变换）：

| | MultiQueryExpander | RewriteQueryTransformer | TranslationQueryTransformer |
|---|---|---|---|
| 接口 | `QueryExpander` | `QueryTransformer` | `QueryTransformer` |
| 输入 | 1 个 Query | 1 个 Query | 1 个 Query |
| 输出 | N 个 Query | 1 个 Query | 1 个 Query |
| 策略 | **扩写**：换角度/措辞 | **重写**：结构化/消歧义 | **翻译**：目标语言 |
| 成本 | 高（N+1 次检索） | 低（1 次检索） | 低（1 次检索） |
| 提升 | 召回率（Recall） | 精度（Precision） | 跨语言覆盖 |
| Builder 特有参数 | `numberOfQueries(int)` | `targetSearchSystem(String)` | `targetLanguage(String)` |

**为什么三个都实现了同一个接口族？** `QueryTransformer` 是 `Function<Query, Query>`（1→1），`QueryExpander` 是 `Function<Query, List<Query>>`（1→N）。Spring AI 把预检索抽象为"对 Query 做变换"的操作，不同实现只是变换策略不同。新增策略只需实现接口，不需要改框架代码——开闭原则。

---

## 3. MultiQueryExpander（多查询扩展）

### 3.1 官方用法

```java
MultiQueryExpander expander = MultiQueryExpander.builder()
        .chatClientBuilder(builder)
        .numberOfQueries(3)
        .includeOriginal(true)
        .build();

List<Query> queries = expander.expand(new Query("怎么提升魅力？"));
// → [Query("怎么提升魅力？"),                     ← 原始查询（includeOriginal=true）
//     Query("提升个人魅力的方法有哪些？"),          ← LLM 生成的变体
//     Query("如何让自己更有吸引力？"),              ←
//     Query("怎样增强自身魅力吸引异性？")]           ←
```

### 3.2 项目中的实际代码

`MultiQueryExpansionAdvisor` 把 Expander 包装成 Advisor，集成到 ChatClient 拦截器链：

```java
// 构造时：创建 Expander（31-40行）
public MultiQueryExpansionAdvisor(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
                                  int queryCount, int topK, double similarityThreshold) {
    this.queryExpander = MultiQueryExpander.builder()
            .chatClientBuilder(chatClientBuilder)    // 复用聊天模型的 Builder
            .numberOfQueries(queryCount)             // 生成 N 个变体
            .build();                                // includeOriginal 默认 true
    this.documentJoiner = new ConcatenationDocumentJoiner();
    this.vectorStore = vectorStore;
    // ...
}
```

```java
// 运行时：扩展 → 多路检索 → 合并去重（52-69行）
@Override
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    String userText = request.prompt().getUserMessage().getText();

    // Step 1: LLM 扩写为 N 个变体 + 原始查询
    List<Query> queries = this.queryExpander.expand(buildQuery(userText, request));

    // Step 2: 每个 Query 各自检索 VectorStore
    Map<Query, List<List<Document>>> documentsForQuery = new LinkedHashMap<>();
    for (Query q : queries) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(q.text()).topK(topK).similarityThreshold(similarityThreshold).build());
        documentsForQuery.put(q, List.of(docs));
    }

    // Step 3: ConcatenationDocumentJoiner 合并去重
    List<Document> allDocs = this.documentJoiner.join(documentsForQuery);

    // Step 4: 注入 Prompt 上下文，继续链条
    ChatClientRequest modified = allDocs.isEmpty() ? request : injectContext(request, userText, allDocs);
    ChatClientResponse response = chain.nextCall(modified);
    response.context().put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, allDocs);
    return response;
}
```

### 3.3 为什么用 ConcatenationDocumentJoiner 而不是 LinkedHashMap 手动去重？

`MultiQueryExpander` 返回的变体可能产生重复文档（两个不同问法搜到同一篇文档）。如果用 `LinkedHashMap<String, Document>` 手动去重：

- 能工作，但不符合 Spring AI RAG 管道的标准架构
- `ConcatenationDocumentJoiner` 是官方组件，内部做了去重 + 保持分数 + 维护插入顺序
- 未来如果需要替换 Joiner 策略（如按分数排序、按来源加权），只需换一个 `DocumentJoiner` 实现，不侵入业务逻辑

**类比**：`Collections.sort()` vs 自己写快排。自己写能跑，但标准库的实现在边界情况、性能、可维护性上更可靠。

### 3.4 执行链路

```
用户："怎么提升魅力？"
  ↓
MultiQueryExpander.expand()
  → ChatClient.prompt().user(模板填入 {query:"怎么提升魅力？"}).call()
  → LLM 返回：每行一个变体
  → 内部按 \n 切分 → 校验数量 == numberOfQueries
  → 构建 List<Query>（includeOriginal=true 时原始查询插入 index=0）
  ↓
4 个 Query，逐一 VectorStore.similaritySearch()
  Query0 ("怎么提升魅力？")         → [docA, docB]
  Query1 ("提升个人魅力的方法")     → [docB, docC]  ← docB 重复
  Query2 ("如何让自己更有吸引力")   → [docA, docD]  ← docA 重复
  Query3 ("怎样增强自身魅力")       → [docE]
  ↓
ConcatenationDocumentJoiner.join()
  → [docA, docB, docC, docD, docE]  ← 去重，保留首次出现顺序
  ↓
注入 Prompt → chain.nextCall() → LLM 回答
```

---

## 4. RewriteQueryTransformer（查询重写）

### 4.1 官方用法

```java
QueryTransformer transformer = RewriteQueryTransformer.builder()
        .chatClientBuilder(builder)
        .targetSearchSystem("vector-store")     // 可选：描述目标检索系统
        .build();

Query rewritten = transformer.transform(new Query("咋搞个能跑的例子"));
// → Query("如何创建一个可运行的 Spring Boot 示例项目")
```

### 4.2 与 MultiQueryExpander 的本质区别

| | 策略 | 比喻 |
|---|---|---|
| MultiQueryExpander | 撒多张网，捞更多鱼 | 用"汽车""轿车""四轮车"分别搜 |
| RewriteQueryTransformer | 磨尖鱼叉，精准叉鱼 | 把"那玩意儿咋整"翻译成"Spring Boot 项目如何启动" |

**为什么选重写而不是扩展？** 当问题是"模糊"而非"多样"时。用户已经知道自己想问什么，只是表述不清。重写后的查询更结构化（含关键词、去口语），单次检索精度显著提升。扩展反而会引入噪声（不准确的变体搜到无关文档）。

### 4.3 项目代码

```java
// RewriteQueryAdvisor.java
@Override
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    String userText = request.prompt().getUserMessage().getText();

    // 1. LLM 重写查询
    Query rewritten = this.queryTransformer.transform(buildQuery(userText, request));
    log.info("RewriteQuery: {} -> {}", userText, rewritten.text());

    // 2. 用重写后的精准查询检索（1 次）
    List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
            .query(rewritten.text()).topK(topK).similarityThreshold(similarityThreshold).build());

    // 3. 注入 → 继续
    // ...
}
```

**为什么用重写后的查询检索但保持原始用户问题不变？** 重写是为了更好地匹配向量库中的文档，但 Prompt 中"用户问题"部分仍用原始表述——LLM 需要知道用户真实的对话语境。如果 Prompt 里也放重写后的版本，可能会改变用户意图。

---

## 5. TranslationQueryTransformer（查询翻译）

### 5.1 官方用法

```java
QueryTransformer transformer = TranslationQueryTransformer.builder()
        .chatClientBuilder(builder)
        .targetLanguage("chinese")
        .build();

Query translated = transformer.transform(new Query("How to ask a girl out?"));
// → Query("如何约女生出去？")
```

### 5.2 适用场景

```
用户（英文）："What are some tips for long distance relationships?"
知识库（中文）：《异地恋沟通技巧》《如何维持异地恋新鲜感》

无翻译：
  → vectorStore.search("What are some tips...") → 召回空（英文搜中文库）

有翻译：
  → TranslationQueryTransformer → "异地恋有什么技巧？"
  → vectorStore.search("异地恋有什么技巧？") → 命中 2 篇文档
```

**为什么不用 embedding 模型自带的多语言能力？** 现代 embedding 模型（如 text-embedding-3-large）确实有一定跨语言能力，但效果不如"先翻译再检索"稳定。embedding 的跨语言能力是"顺便"的，TranslationQueryTransformer 是"专门"的——用 LLM 翻译比 embedding 的隐式跨语言映射更可靠。

### 5.3 项目代码

```java
// TranslationQueryAdvisor.java
public TranslationQueryAdvisor(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
                               String targetLanguage, int topK, double similarityThreshold) {
    this.transformer = TranslationQueryTransformer.builder()
            .chatClientBuilder(chatClientBuilder)
            .targetLanguage(targetLanguage)     // "chinese" → 知识库是中文
            .build();
    // ...
}
```

**targetLanguage 参数**：传裸字符串（如 `"chinese"`），会填入 `TranslationQueryTransformer` 的默认 Prompt 模板。模板大致是"Translate the query to {targetLanguage}"。

---

## 6. CompressionQueryTransformer（查询压缩）

### 6.1 解决的问题

多轮对话中，用户追问常带代词或省略：

```
第1轮：用户："碧海湾小区在哪？" → LLM："在深圳市南山区后海中心区"
第2轮：用户："那二手房均价呢？"   → 直接检索："那二手房均价" → 无命中
```

`CompressionQueryTransformer` 将"对话历史 + 追问"压缩为一个上下文完整的独立查询：

```
第2轮追问："那二手房均价呢？"
  压缩后 → "深圳南山区后海碧海湾小区的二手房均价是多少？"
```

### 6.2 与 RewriteQueryTransformer 的区别

| | RewriteQueryTransformer | CompressionQueryTransformer |
|---|---|---|
| 输入 | 单个模糊查询 | 对话历史 + 追问 |
| 做什么 | 去口语化、结构化 | 消解代词、补充省略信息 |
| 需要 Query.history | 不必须 | **必须**（否则无法压缩） |
| 典型输入 | "咋搞个能跑的例子" | "那这个小区呢？" |
| 典型输出 | "如何创建可运行的示例项目" | "深圳后海碧海湾小区怎么样？" |

**为什么需要两个不同的 Transformer？** 重写关注的是"表述质量"（口语→正式），压缩关注的是"信息完整性"（省略句→完整句）。两者解决不同维度的问题。

### 6.3 官方用法

```java
CompressionQueryTransformer transformer = CompressionQueryTransformer.builder()
        .chatClientBuilder(builder)
        .build();

// Query 必须带 history，否则压缩无意义
Query query = Query.builder()
        .text("那二手房均价呢？")
        .history(List.of(
                new UserMessage("碧海湾小区在哪？"),
                new AssistantMessage("在深圳南山区后海")
        ))
        .build();

Query compressed = transformer.transform(query);
// → "深圳南山区后海碧海湾小区的二手房均价是多少？"
```

### 6.4 项目代码

```java
// CompressionQueryAdvisor.java
public CompressionQueryAdvisor(ChatClient.Builder chatClientBuilder, DocumentRetriever retriever) {
    this.transformer = CompressionQueryTransformer.builder()
            .chatClientBuilder(chatClientBuilder)
            .build();
    this.retriever = retriever;
}

// 关键：从请求中提取对话历史传给 Query
private Query buildQuery(String userText, ChatClientRequest request) {
    List<Message> history = request.prompt().getInstructions().stream()
            .filter(m -> m.getMessageType() != MessageType.SYSTEM)
            .collect(Collectors.toList());
    return Query.builder().text(userText).history(history).build();
}
```

**为什么 CompressionQueryTransformer 强依赖 Query.history？** 压缩的本质是从对话历史中提取关键信息补全追问。没有历史，追问"那这个呢？"永远无法被压缩成有意义的独立查询。其他 Transformer 即使没有历史也能工作，只是效果差一些。

---

## 7. Context-aware Query（上下文感知查询）

### 7.1 问题

```
第1轮：用户："碧海湾小区在哪？"  → LLM："在深圳市南山区后海中心区"
第2轮：用户："那这个小区的二手房均价？"  → expand/rewrite 看不到"碧海湾小区" → 检索失败
```

### 7.2 解决方式

构建 `Query` 时附加上对话历史，让做扩展/重写/翻译的 LLM 能理解上下文：

```java
// 三个 Advisor 共用的 buildQuery 方法
private Query buildQuery(String userText, ChatClientRequest request) {
    List<Message> history = request.prompt().getInstructions().stream()
            .filter(m -> m.getMessageType() != MessageType.SYSTEM)  // 排除 System 消息
            .collect(Collectors.toList());
    return Query.builder()
            .text(userText)
            .history(history)    // ← 对话上下文
            .build();
}
```

### 7.3 效果

```
无上下文：
  Query{text: "那这个小区的二手房均价？", history: []}
  → MultiQueryExpander: "那这个小区的二手房均价？"  → 扩写不出有意义变体
  → 检索："那这个小区" → 无命中

有上下文：
  Query{
    text: "那这个小区的二手房均价？",
    history: [UserMsg("碧海湾小区在哪？"), AsstMsg("碧海湾小区位于...")]
  }
  → MultiQueryExpander 看到对话历史
  → 扩写："碧海湾小区二手房均价""深圳南山区后海二手房价格"  → 命中！
```

**为什么用 `Prompt.getInstructions()` 而不是 `Prompt.getMessages()`？** Spring AI 的 `Prompt` 类没有 `getMessages()`，消息列表通过 `getInstructions()` 获取——方法名来自 `ModelRequest<List<Message>>` 接口。`Prompt` 实现了这个接口，用 `getInstructions` 命名是因为 Prompt 的 messages 就是给模型的"指令"。

### 7.4 为什么 Query.history 不能替代 MessageChatMemoryAdvisor？

| | Query.history | MessageChatMemoryAdvisor |
|---|---|---|
| 作用阶段 | 预检索（Pre-Retrieval） | 对话上下文（整个 Prompt） |
| 谁用 | MultiQueryExpander 等预检索组件 | ChatModel（最终 LLM 调用） |
| 效果 | 让检索更准确 | 让回答有记忆 |
| 是否必选 | 可选，增强检索 | 必需（否则每次对话失忆） |

两者不冲突，是流水线上的两个不同工位。

---

## 8. Retrieval 模块

Spring AI 的 Retrieval 模块负责从数据源检索文档，核心组件：

| 组件 | 接口 | 作用 |
|------|------|------|
| `VectorStoreDocumentRetriever` | `DocumentRetriever` | 封装 VectorStore 检索，支持 topK / similarityThreshold / metadata 过滤 |
| `ConcatenationDocumentJoiner` | `DocumentJoiner` | 多路文档合并去重 |

### 8.1 为什么需要 DocumentRetriever？

之前每个 Advisor 手动调用 `vectorStore.similaritySearch(SearchRequest.builder()...)` — 重复代码、每处都要处理 topK/threshold/filterExpression。`VectorStoreDocumentRetriever` 将检索逻辑封装为标准组件：

```java
// 之前：各 Advisor 手动构造 SearchRequest
List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
        .query(q.text()).topK(topK).similarityThreshold(similarityThreshold).build());

// 之后：统一调用 retriever
List<Document> docs = retriever.retrieve(q);
```

**为什么 retriever.retrieve() 传入整个 Query 对象而非纯文本？** `Query` 不仅是 `text`，还有 `history` 和 `context`。将来 VectorStore 可能利用这些元数据做更智能的检索（如根据历史调整检索策略），传完整对象保留扩展性。

### 8.2 官方用法

```java
DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.5)
        .topK(3)
        .build();

List<Document> documents = retriever.retrieve(new Query("怎么提升魅力？"));
```

### 8.3 项目中的应用

```java
// LoveApp.java — 所有 RAG Advisor 共享同一个 retriever
DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.5)
        .topK(3)
        .build();

new MultiQueryExpansionAdvisor(ChatClient.builder(chatModel), retriever, 3);
new RewriteQueryAdvisor(ChatClient.builder(chatModel), retriever);
new TranslationQueryAdvisor(ChatClient.builder(chatModel), retriever, "chinese");
new CompressionQueryAdvisor(ChatClient.builder(chatModel), retriever);
```

### 8.4 ConcatenationDocumentJoiner

多查询扩展中，每个变体各自检索，结果需要合并去重：

```java
Map<Query, List<List<Document>>> documentsForQuery = new LinkedHashMap<>();
for (Query q : expandedQueries) {
    documentsForQuery.put(q, List.of(retriever.retrieve(q)));
}
// 合并去重：重复文档保留首次出现，分数不变
List<Document> allDocs = new ConcatenationDocumentJoiner().join(documentsForQuery);
```

**为什么是 `Map<Query, List<List<Document>>>` 这种嵌套结构？** 每个 Query 可能对应多个数据源（如 PGVector + Redis + 百炼云），所以是 `List<List<Document>>`（外层是源，内层是文档）。当前项目用单个 VectorStore，所以每 Query 只有 1 个源。

---

## 9. Post-Retrieval + Generation 模块

Pre-Retrieval → Retrieval → **Post-Retrieval** → **Generation** 组成完整 RAG 流水线。后两个模块负责将检索到的文档转化为 LLM 可用的上下文。

### 9.1 粗排精排两阶段检索（MultiQueryExpansionAdvisor 完整链路）

项目 MULTI 策略是唯一打通 **粗排 + 精排** 完整链路的策略。核心思路：**粗排牺牲精度保覆盖率，精排把噪声过滤掉只留最相关的**。

**完整执行流程**（`MultiQueryExpansionAdvisor.java:63-91`）：

```
用户问题："Spring @Transactional 什么情况下会失效？"
│
├─ Step 1: MultiQueryExpander — LLM 扩写 N 个查询变体
│   Q0: "Spring @Transactional 什么情况下会失效？"        ← 原始查询（includeOriginal=true）
│   Q1: "Spring 事务注解失效的常见场景有哪些？"            ← LLM 生成
│   Q2: "@Transactional 不生效的原因是什么？"             ← LLM 生成
│   Q3: "Spring Boot 事务回滚失败的情况有哪些？"          ← LLM 生成
│
├─ Step 2: 粗排 — 低阈值多路召回（coarseRetriever）
│   参数：threshold=0.35（SINGLE 策略是 0.5）, topK=5（SINGLE 是 3）
│   每个 Query 各自检索：
│     Q0 → [docA(0.82), docB(0.61), docC(0.48), docD(0.41), docE(0.37)]
│     Q1 → [docB(0.79), docF(0.55), docC(0.51), docG(0.44), docH(0.39)]
│     Q2 → [docA(0.91), docI(0.53), docF(0.47), docJ(0.42), docK(0.36)]
│     Q3 → [docL(0.63), docB(0.58), docM(0.45), docN(0.40), docA(0.38)]
│   docA 被 3 个查询命中、docB 被 3 个查询命中...
│
├─ Step 3: ConcatenationDocumentJoiner — 去重合并
│   去重后：约 12 条独立文档（从 4×5=20 条去重到 ~12 条）
│
├─ Step 4: 精排 — RerankingDocumentPostProcessor
│   调 DashScope Rerank API（专用 Cross-encoder，< 100ms）
│   输入：12 条候选 + 原始用户问题
│   按相关性重新打分排序 → 截断 topN=3
│   → [docA(0.94), docB(0.87), docF(0.73)]  ← 只保留最相关的 3 条
│
└─ Step 5: ContextualQueryAugmenter — 拼入 Prompt → LLM 回答
```

**粗排和精排检索器的参数对比**（`ChatClientFactory.java:106-111`）：

```java
// SINGLE 策略用的"精检索器"：高阈值少召回
DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
    .similarityThreshold(0.5).topK(3)   // 精准，宁可漏不可错
    .build();

// MULTI 策略用的"粗检索器"：低阈值多召回
DocumentRetriever coarseRetriever = VectorStoreDocumentRetriever.builder()
    .similarityThreshold(0.35).topK(5)  // 宽泛，宁可错不可漏
    .build();
```

两个检索器的阈值差（0.35 vs 0.5）是关键——粗排把门槛放低让更多候选进来，精排负责把质量差的筛掉。

**RerankingDocumentPostProcessor 实现**（`RerankingDocumentPostProcessor.java:36-57`）：

```java
@Override
public List<Document> process(Query query, List<Document> documents) {
    // 候选 ≤ topN 时直接返回，省一次 API 调用
    if (documents.size() <= topN) {
        log.info("Reranking: 候选文档数 {} <= topN {}，跳过", documents.size(), topN);
        return documents;
    }

    // 调 DashScope Rerank API（专用评分模型，< 100ms）
    RerankRequest request = new RerankRequest(query.text(), documents);
    RerankResponse response = rerankModel.call(request);

    // 按相关性得分排序 → 截断 topN
    return response.getResults().stream()
        .map(DocumentWithScore::getOutput)
        .limit(topN)
        .toList();
}
```

**为什么用专用 Rerank API 而不是让 LLM 重排序？**

| | LLM 重排序 | DashScope Rerank API |
|---|---|---|
| 原理 | Prompt："请对这 12 条文档打分" | 专用 Cross-encoder 模型 |
| 延迟 | ~500ms（通用 LLM 推理） | **< 100ms**（专用小模型） |
| 成本 | 高（12 条文档全部作为 prompt token） | 低（专用接口，按调用计费） |
| 精度 | 中（可能被 prompt 长度影响，位置偏差） | **高**（专门训练做 (query, doc) 相关性打分） |

Rerank 模型本质上是一个轻量级 Cross-encoder：把 (query, document) 成对输入，直接输出相关性分数。这比用 LLM 打分快 5 倍以上，且任务专一、精度更高。

**参数取值的设计依据**：

单路粗召回 topK=5 × 4 个变体 = 最多 20 条候选，去重后约 10-15 条。精排截断到 topN=3——粗排候选是精排结果的 3-5 倍，既能保证覆盖率（Rerank API 有足够的文档可选），又不至于塞太多文档导致 Rerank 延迟升高。

**为什么 MULTI 策略把 RerankingDocumentPostProcessor 设为可选**（`@Autowired(required = false)`）？

```java
// ChatClientFactory.java:33-34
public ChatClientFactory(ChatModel chatModel, ChatMemoryRepository repository,
                         VectorStore vectorStore,
                         @Autowired(required = false) RerankModel rerankModel) {
```

```java
// ChatClientFactory.java:110-111
RerankingDocumentPostProcessor reranker = rerankModel != null
    ? new RerankingDocumentPostProcessor(rerankModel, 3) : null;
```

```java
// MultiQueryExpansionAdvisor.java:78-82
if (this.reranker != null) {
    allDocs = this.reranker.process(userQuery, allDocs);
}
```

DashScope Rerank API 需要额外的依赖（`spring-ai-alibaba-starter-dashscope-rerank`）和 API Key。如果未配置，MULTI 策略降级为"多查询扩展 + 去重合并"——仍然比 SINGLE 策略覆盖面广。这是容错设计。

**面试回答模板**：

> "多查询扩展 + 粗排精排的核心思路：用 LLM 把用户问题扩写成多个变体，每个变体用低阈值（0.35）多召回（topK=5）做粗排——牺牲精度保覆盖率。去重合并后交给 DashScope Rerank API 做精排——这是个专用 Cross-encoder 模型，单次调用不到 100ms，比用 LLM 打分快 5 倍。最终截断到 top 3 注入 Prompt。对比 SINGLE 策略的单次检索（threshold=0.5/topK=3），MULTI 能覆盖不同措辞的文档，精排保证了排序质量。另外 Rerank 作为可选依赖，未配置时自动降级为纯多查询扩展——主流程不受影响。"

### 9.2 ContextualQueryAugmenter（Generation）

`QueryAugmenter` 是 Generation 模块的核心，负责将检索到的文档注入查询上下文：

```
Query + List<Document> → QueryAugmenter.augment() → Query（text 已是包含上下文的完整 Prompt）
```

项目用 `ContextualQueryAugmenter` 替换了之前手写的 `injectContext()`：

```java
// 之前：手动拼接
String context = docs.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
String augmented = String.format("## 参考知识\n%s\n\n## 用户问题\n%s", context, userText);

// 之后：标准组件
this.queryAugmenter = ContextualQueryAugmenter.builder()
        .allowEmptyContext(true)   // 允许文档为空时继续回答（而非拒绝）
        .build();
Query augmented = this.queryAugmenter.augment(query, docs);
// augmented.text() → LLM 可直接消费的增强 Prompt
```

**为什么设 `allowEmptyContext(true)`？** 默认 `false` 时，检索不到文档 → LLM 被告知"不要回答"。这对知识库驱动场景更安全，但本项目兼有闲聊功能（如 MemoryTest 测试），设为 `true` 让 LLM 即使没有检索到也可以凭训练知识回答。

### 9.3 完整 RAG 流水线（以 CompressionQueryAdvisor 为例）

```java
// CompressionQueryAdvisor.adviseCall() — 完整的 4 阶段流水线
String userText = request.prompt().getUserMessage().getText();

// Stage 1: Pre-Retrieval — 压缩对话历史 + 追问 → 独立查询
Query compressed = this.transformer.transform(buildQuery(userText, request));

// Stage 2: Retrieval — 向量库检索
List<Document> docs = retriever.retrieve(compressed);

// Stage 3: Post-Retrieval — 文档后处理（当前无操作，预留插槽）
// docs = documentPostProcessor.process(compressed, docs);

// Stage 4: Generation — 文档上下文注入查询
Query augmented = this.queryAugmenter.augment(buildQuery(userText, request), docs);

// 送入 Advisor Chain 下一环节
ChatClientRequest modified = request.mutate()
        .prompt(request.prompt().augmentUserMessage(augmented.text()))
        .build();
ChatClientResponse response = chain.nextCall(modified);
```

**为什么每个 Advisor 各自持有 QueryAugmenter 实例而不是共享？** 当前 4 个 Advisor 都用相同的 `allowEmptyContext(true)` 配置，理论上可共享。但独立持有更灵活——将来可能需要不同的 Prompt 模板（如重写场景用"基于结构化查询的上下文"模板），各 Advisor 独立配置互不影响。

---

## 10. 如何在 Advisor Chain 中切换策略

### 10.1 整体架构

项目用策略枚举 + 统一入口，6 个 ChatClient 在构造时预设 Advisor 链：

```java
public enum RagStrategy {
    NONE,       // 无 RAG
    SINGLE,     // QuestionAnswerAdvisor（单查询）
    REWRITE,    // RewriteQueryAdvisor（查询重写）
    TRANSLATE,  // TranslationQueryAdvisor（查询翻译）
    COMPRESS,   // CompressionQueryAdvisor（查询压缩）
    MULTI       // MultiQueryExpansionAdvisor（多查询扩展）
}

public RagChatResult doChat(String message, String chatId, RagStrategy strategy) {
    ChatClient client = switch (strategy) {
        case SINGLE    -> this.ragChatClient;
        case MULTI     -> this.multiQueryRagChatClient;
        case REWRITE   -> this.rewriteRagChatClient;
        case TRANSLATE -> this.translationRagChatClient;
        case COMPRESS  -> this.compressionRagChatClient;
        case NONE      -> this.chatClient;
    };
}
```

### 10.2 使用的包结构

```
advisor/
├── MultiQueryExpansionAdvisor.java   # 多查询扩展（Expander + Retriever + Joiner）
├── RewriteQueryAdvisor.java          # 查询重写（Transformer + Retriever）
├── TranslationQueryAdvisor.java      # 查询翻译（Transformer + Retriever）
├── CompressionQueryAdvisor.java      # 查询压缩（Transformer + Retriever）
├── ReReadingAdvisor.java             # RE2 增强
├── MyLogAdvisor.java                 # 日志拦截
└── PermissionAdvisor.java            # 权限校验
```

**为什么每个预检索技术独立一个 Advisor 而不是合并成一个？**

- 单一职责：每个 Advisor 只做一件事，测试、修改互不影响
- 可组合：通过策略枚举自由切换，不需要 if-else 分支
- Advisor Chain 本身是责任链模式——新增策略不影响现有链条

### 10.3 Advisor Chain 执行时序

```
order=0  MessageChatMemoryAdvisor  → 注入历史
order=1  [RAG Advisor]             → 预检索 + 检索 + 上下文注入
order=2  ReReadingAdvisor          → RE2 重复问题
order=3  MyLogAdvisor              → 日志
         ───── 调 LLM ─────
order=3  MyLogAdvisor.after()
order=2  ReReadingAdvisor.after()
order=1  [RAG Advisor].after()     → 把检索文档放入 response.context()
order=0  MessageChatMemoryAdvisor.after() → 保存新消息
```

**为什么 RAG Advisor 排在 order=1？** 必须在 `MessageChatMemoryAdvisor`（order=0）之后——因为 `buildQuery()` 需要从 Prompt 中提取对话历史，而这段历史是 MessageChatMemoryAdvisor 在 order=0 注入的。顺序反了会导致 Query.history 为空。

---

## 11. 策略选择指南

```
用户问题来了
  │
  ├─ 闲聊/倾诉/打招呼？
  │     → NONE（无 RAG）
  │
  ├─ 知识库是中文，用户用英文问？
  │     → TRANSLATE
  │
  ├─ 多轮对话，追问带代词/省略？
  │     → COMPRESS（压缩历史 + 追问）
  │
  ├─ 问题模糊、口语化严重？
  │     → REWRITE
  │
  ├─ 问题复杂，需要多角度查资料？
  │     → MULTI
  │
  └─ 问题清晰、关键词明确？
        → SINGLE（默认，最简单）
```

---

## 12. 核心对象速查

| 对象 | 所在包 | 接口 | 作用 |
|------|-------|------|------|
| `MultiQueryExpander` | `spring-ai-rag` prereieval | `QueryExpander` | 1→N 查询扩写 |
| `RewriteQueryTransformer` | `spring-ai-rag` prereieval | `QueryTransformer` | 1→1 模糊→结构化 |
| `TranslationQueryTransformer` | `spring-ai-rag` prereieval | `QueryTransformer` | 1→1 翻译 |
| `CompressionQueryTransformer` | `spring-ai-rag` prereieval | `QueryTransformer` | 对话历史+追问→独立查询 |
| `VectorStoreDocumentRetriever` | `spring-ai-rag` search | `DocumentRetriever` | 封装 VectorStore 检索 |
| `ConcatenationDocumentJoiner` | `spring-ai-rag` join | `DocumentJoiner` | 多路文档合并去重 |
| `DocumentPostProcessor` | `spring-ai-rag` postretrieval | `BiFunction<Q,List<D>,List<D>>` | 文档后处理（重排序/去冗余/压缩） |
| `ContextualQueryAugmenter` | `spring-ai-rag` generation | `QueryAugmenter` | 文档上下文注入查询 |
| `Query` | `spring-ai-rag` | — | 查询实体（text + history + context） |
| `ChatClient.Builder` | `spring-ai-client-chat` | — | 预检索组件共用的依赖注入方式 |

---

## 13. 常见问题

**Q: 为什么不把 REWRITE + MULTI 串联（先重写再扩展）？**
技术上可行。但每次 LLM 调用都有成本 + 延迟。当前阶段先验证各自效果，后续根据实际数据决定是否串联。过早优化是万恶之源。

**Q: numberOfQueries 设多少合适？**
3~5 个。每多一个变体 = 多一次 VectorStore 检索 = 多一次网络 IO + 向量计算。设太大（10+）性价比低——多数额外变体搜不到新文档。

**Q: 自定义 Prompt 模板 vs 默认模板？**
默认模板经过测试，大多数场景够用。自定义模板的优势是可以用中文（更适配中文知识库）、添加特定约束。代价是需要维护、需要知道模板里必须有 `{number}` `{query}` 等占位符。项目当前用默认模板。

**Q: 上下文感知 Query 会不会让 Prompt 太长？**
`Query.history` 只传给预检索阶段的 LLM（做扩写/重写/翻译），不传给最终回答的 LLM。所以不会撑爆最终 Prompt 的 token 限制。
