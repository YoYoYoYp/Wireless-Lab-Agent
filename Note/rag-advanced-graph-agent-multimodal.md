# RAG 进阶：Graph RAG、Agent RAG 与多模态 ETL

> 面试场景题：Graph RAG / Agent RAG 有什么区别？你为什么不用？文档里有图片怎么办？
> 项目代码：当前为 Traditional RAG（`ChatClientFactory.java` + 6 种策略路由）

---

## 1. 三种 RAG 架构对比

### 1.1 Traditional RAG（项目当前方案）

```
User → Query → VectorStore.similaritySearch → Top-K Docs → LLM
                ↑ 基于向量相似度
```

**核心机制**：用户问题 → embedding → 和知识库向量算 COSINE 距离 → 返回最相似的 K 条文档 → 拼进 Prompt → LLM 回答。

**项目增强**：不是"一个 query 搜一次"，而是 LLM 预分类自动选策略：

```java
// InterviewApp.java:110-123
public Flux<String> doChat(String message, String chatId, String userId) {
    RagStrategy strategy = classifyQuery(message);  // LLM 自动分类
    return clients.get(strategy)                    // NONE/SINGLE/MULTI/REWRITE/TRANSLATE/COMPRESS
            .prompt().user(message)
            .advisors(...)
            .stream().content();
}
```

**强项**：简单、延迟低、单次检索 ms 级。
**局限**：只能找"语义相似"的内容，处理不了需要综合多个文档的推理类问题。

### 1.2 Graph RAG（微软 GraphRAG）

```
离线阶段：
  Documents → LLM 抽取实体 + 关系 → 知识图谱（Neo4j）
           → LLM 生成社区摘要 → 层次化社区结构

在线检索：
  User → Query → 匹配社区摘要 / 遍历图关系 → 结构化上下文 → LLM
                 ↑ 基于实体关系，非向量相似度
```

**强项**：

- **多跳推理（Multi-hop）**：能处理"A 和 B 什么关系？"这类需要跨越多个文档的问题
- **全局摘要（Global Summarization）**：能回答"整个数据集的核心主题是什么？"

**示例**：

```
问："Spring 中哪些设计模式用到了动态代理？"

Traditional RAG：
  → 向量检索搜"设计模式 动态代理" → 可能只找到 1 篇相关文档
  → LLM 只能基于这 1 篇回答

Graph RAG：
  → 遍历图：Spring → AOP → 动态代理 → JDK代理 → InvocationHandler
                      → CGLIB代理 → MethodInterceptor
                      → @Transactional → TransactionInterceptor
  → 自动关联出 3 个设计模式的完整链路
  → LLM 基于完整知识链回答
```

**构建流程**：

```
1. 实体抽取：
   文档 → LLM → [{entity: "Spring AOP", type: "框架组件"},
                  {entity: "动态代理", type: "设计模式"},
                  {entity: "@Transactional", type: "注解"}]

2. 关系抽取：
   LLM → [{source: "Spring AOP", target: "动态代理", relation: "使用"},
           {source: "@Transactional", target: "Spring AOP", relation: "依赖"}]

3. 社区发现（Leiden 算法）：
   图 → 分层聚类 → 层次化社区结构

4. 社区摘要（最贵的步骤）：
   每个社区 → LLM 生成摘要 → 作为检索入口
```

**为什么项目不用**：

| 维度 | Traditional RAG | Graph RAG |
|------|----------------|-----------|
| 知识库规模 | 适合中小（百篇） | 适合大规模（千篇+） |
| 查询类型 | 事实查找、概念解释 | 多跳推理、全局摘要、What-if |
| 构建成本 | 低 | **极高**（LLM 逐条抽实体+关系+社区摘要） |
| 检索延迟 | ms 级 | 中高（图遍历+社区匹配） |
| 增量更新 | 加文档 embed 就行 | 加文档要更新图 + 重新生成社区摘要 |

无线实验室的大部分查询是“USRP采样率如何设置”“QPSK实验步骤是什么”——典型事实查找，Traditional RAG完全够用。

### 1.3 Agent RAG（Self-RAG / CRAG / Adaptive RAG）

```
无固定管线，LLM 自主决策每一步：

User → Agent（LLM）
        ├─ "我需要检索吗？" → 决策节点
        ├─ "搜什么？" → 自动生成查询关键词
        ├─ "搜到了，能用吗？" → 自我评估检索质量
        ├─ "不够，换关键词再搜" → 重试循环
        └─ "够了，开始回答" → 综合多轮检索结果
```

**强项**：处理"开放域复杂问题"——需要多次检索 + 推理 + 验证。

**示例**：

```
问："对比 Spring Boot 和 Quarkus 在云原生场景下的优劣"

Traditional RAG：
  → 搜"Spring Boot Quarkus 云原生对比" → 找到 2 篇 → 回答（可能缺数据）

Agent RAG：
  → Agent：我需要分步来
    Step 1: 搜"Spring Boot 云原生特性" → 3 篇
    Step 2: 搜"Quarkus 云原生特性" → 2 篇
    Step 3: 评估：缺少启动时间对比数据
    Step 4: 搜"Spring Boot vs Quarkus 启动时间" → 1 篇
    Step 5: 够了，综合三批结果 → 给出带数据的对比回答
```

**为什么项目不用**：

Agent RAG有多轮LLM调用和多轮检索，**首token延迟高**。实验操作要求及时反馈，一次分类、一次检索和一次生成的延迟更可控。当前采用**轻量预分类 + 多策略路由**，用一次决策覆盖主要场景。

### 1.4 三种方案对比总结

```
                        复杂度              延迟              适用场景
                        ─────────────────────────────────────────────
Traditional RAG  ▓░░░░ 低           ▓░░░░ 低（ms）     事实查找、FAQ
Graph RAG        ▓▓▓▓░ 高（建图）   ▓▓░░░ 中           多跳推理、全局摘要
Agent RAG        ▓▓▓░░ 中           ▓▓▓▓░ 高（多轮）    开放域复杂问题
```

### 1.5 项目的实际选择 —— 一次决策 + 策略路由

不走 Agent RAG 的多轮循环，但也不是最简单的单次检索：

```
用户消息
  → classifyQuery()  ← 一次 LLM 轻量调用
    分类结果：CHAT / FACT / FOLLOW_UP / COMPLEX
  → 路由到对应策略：
     CHAT → NONE（纯对话，不检索）
     FACT → SINGLE（单次检索）
     FOLLOW_UP → COMPRESS（压缩历史+检索）
     COMPLEX → MULTI（多查询扩展+粗排+精排）
  → 一次检索 + 一次生成 → 返回
```

**设计思路**：用一次轻量 LLM 调用替代 Agent RAG 的多轮决策——在延迟和效果间找平衡点。复杂查询（COMPLEX）走 MultiQueryExpansion 弥补单次检索的覆盖面不足。

::: tip 面试金句
"Graph RAG和Agent RAG我都调研过。当前设备说明和实验流程以事实检索为主，向量检索可以直接命中。Graph RAG适合多跳关系推理，但构建和查询成本更高；Agent RAG的多轮自省也会增加首token时间。因此项目采用轻量分类路由和多查询扩展，在效果、延迟和维护成本之间取平衡。"
:::

---

## 2. 多模态 ETL：文档里有图片怎么办

当前项目的 ETL 是纯文本的：

```
TikaDocumentReader → 正则清洗 → TokenTextSplitter → KeywordMetadataEnricher → 写入
```

**Tika 能读图片吗？** 不能——Tika 只提取文本，图片里的内容（架构图、流程图、截图）会被完全忽略。这是当前方案的根本局限。

### 2.1 先分类：图片分两种，处理方式完全不同

| 图片类型 | 示例 | 核心信息 | 处理策略 |
|---------|------|---------|---------|
| 信息型 | 架构图、流程图、数据表格截图 | 图中文字 + 结构关系 | 多模态 LLM 描述 |
| 装饰型 | Logo、背景图、表情包 | 无价值 | 过滤掉 |

### 2.2 方案一：多模态 LLM 转文字描述（推荐起步方案）

```
图片 → 多模态 LLM（GPT-4V / DashScope 多模态） → 文字描述 → 和原文拼接 → 继续现有 ETL
```

伪代码：

```java
for (Resource resource : resources) {
    // 1. Tika 提取文本（图片位置可能被标记为占位符或留下线索）
    TikaDocumentReader reader = new TikaDocumentReader(resource);
    List<Document> docs = reader.read();

    // 2. 单独提取文档中的图片（PDFBox / POI / Aspose）
    List<Image> images = extractImages(resource);

    // 3. 对每张图片调用多模态 LLM 描述
    for (Image img : images) {
        String description = multimodalModel.call(
            "请描述这张图片的内容，包括：图中文字、结构关系、核心信息。"
            + "图片上下文：" + nearbyText
        );
        // 4. 将描述文本插入到原文对应位置
        insertDescriptionIntoDoc(docs, img.getPosition(), description);
    }

    // 5. 后续流程不变：清洗 → 切分 → 关键词 → 写入
}
```

**关键细节**：

- 图片要保留**位置上下文**——图片描述必须插入到原文对应段落附近，embedding 时才能捕捉"这张图属于哪段内容"
- 多模态 LLM 要给出**带语义的描述**："这是一个微服务架构图，包含 API Gateway、3 个业务服务和 1 个 PostgreSQL 数据库，服务间通过 gRPC 通信"——而不是"这是一张图片"
- 成本可控：万级文档假设平均 3 张图，3 万次多模态调用，在可接受范围

### 2.3 方案二：端到端多模态 Embedding（前沿）

不转文字，直接对图片做向量化：

```
文本 → Text Embedding  ─┐
                         ├─→ 混合向量库（CLIP 模型）
图片 → Image Embedding ─┘
```

CLIP 模型可以把文本和图片映射到**同一个向量空间**，用户用文字提问可以直接搜到相关图片。

**优点**：省掉"图片→文字"中间步骤，用户文字提问直接搜到图。
**缺点**：CLIP 中文能力弱于专用 Embedding，pgvector 不区分"向量来自文本还是图片"。

### 2.4 方案三：混合架构（生产推荐）

```
PDF/DOCX
  ├─ 文本 → 现有 ETL Pipeline → PGVector（文本向量）
  └─ 图片 → 提取 → 多模态 LLM 描述
                  ├─ 描述文本 → 插入原文 → 同上
                  └─ 原图 → 存 OSS/S3 → URL 写入 metadata
```

检索时：先搜文本向量找到相关 chunk → 返回给 LLM 时带上下文的图片 URL → LLM 回答中引用图片链接。

### 2.5 方案选择指南

| 方案 | 复杂度 | 成本 | 适用场景 |
|------|-------|------|---------|
| 多模态 LLM 转文字 | 低（复用现有 ETL） | 中（每图一次 LLM 调用） | 信息型图片为主 |
| 多模态 Embedding | 高（新模型+新索引） | 高 | 图片本身是答案（电商） |
| 混合架构 | 中高 | 中高 | 既要检索又要看原图 |

::: tip 面试金句
"当前 ETL 只处理文本，多模态改造我分三步走：第一步，信息密度最高的架构图和流程图，用多模态 LLM 转文字描述插入原文——改动最小、兼容现有管线；第二步，原图存对象存储，metadata 记录 URL，检索时带上图片引用；第三步，如果图片搜索需求大，引入 CLIP 做多模态 embedding。选方案的关键不是技术炫，是评估图片在场景里到底承载多少信息——面试知识库的图片是架构图，转文字就够了；电商场景图片本身就是答案，必须上多模态 embedding。"
:::

---

## 3. 延伸：RAG 策略选择速查

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
  │     → MULTI（多查询扩展 + 粗排精排）
  │
  └─ 问题清晰、关键词明确？
        → SINGLE（默认，最简单）
```

## 4. 核心概念速查

| 概念 | 一句话 | 项目状态 |
|------|-------|---------|
| Traditional RAG | 向量检索 + LLM 生成 | ✅ 当前方案 |
| Graph RAG | 知识图谱 + 实体关系，处理多跳推理 | ❌ 构建成本高，场景不匹配 |
| Agent RAG | LLM 自主决策检索-评估-重试循环 | ❌ 延迟高，场景不匹配 |
| 多模态 ETL | 图片转文字后融入现有管线 | ❌ 待实现 |
| CLIP | 文本和图片映射到同一向量空间 | ❌ 待评估 |
