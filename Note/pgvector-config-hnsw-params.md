# PostgreSQL + pgvector 向量库配置与 HNSW 参数详解

> 面试场景：基于 PostgreSQL + pgvector 搭建本地知识库，配置 1024 维向量、COSINE_DISTANCE 与 HNSW 索引。
> 项目代码：`PgVectorConfig.java`、`RagConfig.java`、`KnowledgeBaseInitializerConfig.java`

---

## 1. 架构选型：为什么用 PostgreSQL + pgvector

| 方案 | 优点 | 缺点 |
|------|-----|------|
| 专用向量库（Milvus/Pinecone） | 检索性能极致，十亿级 | 多一套运维、多一套连接、数据一致性跨库协调 |
| PostgreSQL + pgvector | **向量和业务数据共库**，运维简单 | 十亿级以上性能不如专用库 |
| Redis + RediSearch | 低延迟，缓存+向量二合一 | 内存成本高，持久化弱 |

面试项目的业务数据（用户、会话）本来就存在 PostgreSQL 里，让向量和业务数据共库，省掉一个外部依赖。万级文档，pgvector 完全够用。

::: tip 面试金句
"选 pgvector 不是因为它最强，是因为满足需求的前提下复杂度最低——运维一套数据库就够了。"
:::

---

## 2. 向量库配置三要素

代码在 `PgVectorConfig.java:17-37` 和 `RagConfig.java:20-29`：

```java
@Bean
public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
    return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .vectorTableName("vector_store")
            .dimensions(1024)                                          // ①
            .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE) // ②
            .indexType(PgVectorStore.PgIndexType.HNSW)                  // ③
            .initializeSchema(true)
            .batchingStrategy(new TokenCountBatchingStrategy(
                    EncodingType.CL100K_BASE, 800, 0.1))
            .build();
}
```

### 2.1 dimensions = 1024：为什么是这个数

```yaml
# application-local.yml:13
spring.ai.dashscope.embedding.model: text-embedding-v4  # 1024 维
```

维度不是拍脑袋选的，是 Embedding 模型决定的。选阿里 DashScope `text-embedding-v4`，它输出的向量固定 1024 维。改模型就得改这里——高度耦合，所以集中管理。

### 2.2 distanceType = COSINE_DISTANCE：为什么不用 L2

pgvector 支持三种距离：

| 距离类型 | 公式思路 | 适用场景 |
|---------|---------|---------|
| L2（欧几里得） | 空间直线距离，`√Σ(aᵢ-bᵢ)²` | 向量大小有意义时（图像像素值） |
| COSINE（余弦） | 夹角大小，`1 - a·b/(|a|·|b|)` | **文本语义相似度** |
| INNER_PRODUCT（内积） | 点积，`a·b` | 向量已归一化时 |

**文本嵌入的核心是方向，不是大小。** 两个句子语义相近 → 向量方向接近（夹角小），但向量模长可能差别很大（句子长度不同）。COSINE 只看夹角不看长度，天然适合。如果选了 L2，长文本和短文本的向量天然距离远，即使语义相近也会被误判。

检索时的实际 SQL：

```sql
SELECT id, content, metadata, 1 - (embedding <=> ?) AS similarity
FROM vector_store
WHERE 1 - (embedding <=> ?) > 0.5          -- COSINE 阈值过滤
ORDER BY embedding <=> ?                    -- 余弦距离升序（越小越相似）
LIMIT 3;
```

`<=>` 是 pgvector 的余弦距离运算符。`1 - 距离 = 相似度`。

### 2.3 indexType = HNSW：为什么不用 IVFFlat

| | IVFFlat | HNSW |
|---|---|---|
| 原理 | K-means 聚类分桶，只在最近的桶里搜 | 多层图结构，逐层贪心搜索 |
| 构建速度 | 快（聚类一次完成） | 慢（逐个插入建图） |
| 查询速度 | 中（桶内暴力搜） | **快**（图结构天然适合 ANN） |
| 召回率 | 90-95% | **95-99%** |
| 内存占用 | 低（只存聚类中心） | 高（存图结构） |

面试场景：用户提问后等答案 → **查询延迟优先，召回率优先**。万级文档的索引构建成本一次启动完成，可接受。

---

## 3. HNSW 参数图解：m 和 ef_construction

### 3.1 先理解 HNSW 在干什么

HNSW = Hierarchical Navigable Small World。把图想象成**高速公路 + 国道 + 小路**三层路网：

```
第 2 层（高速公路）：只挑极少数"枢纽"节点 —— 比如 100 个
         ●                         ●
            ↘                   ↗
               ● ─────────── ●
            ↗                   ↘
         ●                         ●
    → 一跳从城东到城西，快速定位大方向


第 1 层（国道）：挑稍多一些节点 —— 比如 1000 个
        ●──●──●──●
        │\ | /|\ |/
        ●──●──●──●──●
          /|\ | /|\
        ●──●──●──●
    → 从高速出口下来，定位到"大概哪个街区"


第 0 层（小路）：全部 10 万个节点都在
        ●─●─●─●─●─●
        │\/|\/|\/|\/|
        ●─●─●─●─●─●─●
        |\/|\/|\/|\/|\|
        ●─●─●─●─●─●─●
    → 精确走到具体门牌号
```

**搜索过程就是一层层往下跳：**

```
起点 Q 在顶层 →
  走 2 步 → 找到最近的高速出口 A
    ↓ 下到第 1 层，从 A 继续
  走 3 步 → 找到最近的国道出口 B
    ↓ 下到第 0 层，从 B 继续
  走 4 步 → 找到目标 C → 结束
```

这就是 H（Hierarchical，分层）的含义——少数"枢纽节点"搭骨架，快速缩小范围。

### 3.2 m = 16：每个节点最多连多少个邻居

**m 控制的是"每个路口有多少条路可以走"：**

```
m = 2（太小）：               m = 16（适中）：

  A ── B ── C ── D            A ── B ── C ── D
       │                       |\ | /|\ |/ |
       E                       | \|/ | \|/ |
                               F──E──G──H──I
  从 A 到 E：
  只能 A→B→E，没别的路。         从 A 到 I：
  如果 B→E 方向不好，绕远路。    多条路可选，总有一条近的。
```

**现实类比**：
- m=2 像你家门口只有一条路通出去，去哪都得先走这条路，经常绕远
- m=16 像市中心路网密集，东南西北都有路，往任何方向都方便

**m 的影响**：

| m 值 | 图结构 | 构建速度 | 查询速度 | 召回率 | 内存 |
|------|--------|---------|---------|--------|------|
| 2 | 稀疏，易死胡同 | 快 | 慢（绕路） | 低 | 小 |
| 16（默认） | 适中 | 中 | 快 | 95%+ | 中 |
| 64 | 非常密 | **极慢** | 极快 | 99%+ | **大** |

### 3.3 ef_construction = 64：建图时找邻居的考察范围

这需要把"建图"拆开看。建图 = 逐个插入节点：

```
插入新节点 X：

  step 1: 在已有图中搜索"离 X 最近的候选节点"
          候选列表的大小由 ef_construction 控制

  step 2: 从候选节点中选 m 个最近的，X 和它们连边
```

**ef_construction 的本质目的**：让 X 插入时能从更多候选里挑出**真正最合适的 16 个邻居**，保证连边质量。

拿一个具体例子走一遍。假设图里已有 100 个点，要插入第 101 个点 X：

```
ef_construction = 10（省事版）：

  搜完后候选队列里有 10 个点：
  ┌─────────────────────────┐
  │ A(0.12)  C(0.18)  E(0.21)│  ← 距离从小到大排
  │ G(0.25)  B(0.31)  H(0.35)│
  │ D(0.40)  J(0.44)  F(0.49)│
  │           I(0.52)         │
  └─────────────────────────┘

  从这 10 个里挑 m=16 个 → 只能选出 10 个
  X 和这 10 个连边

  问题：候选池太小，搜索提前终止。图外面可能还有更近的点
  （比如 M(0.09)、K(0.11)），但候选池满了，它们没进来
  → 连边质量差 → 以后查询走这条路会绕远


ef_construction = 64（靠谱版）：

  搜完后候选队列里有 64 个点：
  ┌──────────────────────────────────────────────┐
  │ A(0.12) C(0.18) E(0.21) G(0.25) B(0.31) ... │  ← 前 10 个和上面一样
  │ ... K(0.11) M(0.09) P(0.14) ...              │  ← 继续搜，发现了更近的！
  │ ... 共 64 个候选                              │
  └──────────────────────────────────────────────┘

  从 64 个里挑 m=16 个 → M(0.09), K(0.11), A(0.12), P(0.14) ...
  真正最优的 16 个 → 连边质量好 → 查询时总能走最短路径
```

**本质：这是一个"搜索深度"参数**。ef_construction 越大，插入每个点时在图里搜得越深越广：

```
ef_construction=10:          ef_construction=64:

  搜了 10 步就停了             搜了 64 步才停
  看到了 10 个点               看到了 64 个点
  从 10 个里选 16 个邻居       从 64 个里选 16 个邻居
       ↓                            ↓
  16 个邻居的质量 ≈ 及格        16 个邻居的质量 ≈ 最优
```

**ef_construction 的影响**：

| ef_construction | 含义 | 构建时间 | 图质量 | 最终查询效果 |
|-----------------|------|---------|--------|------------|
| 10 | 搜索范围小，候选池容易满 | 快 | 粗糙（可能错过真正近的点） | 召回率较低 |
| 64（默认） | 搜索范围适中 | 中 | 好 | 95%+ |
| 200 | 搜索范围大，候选池充足 | **慢** | 很好 | 98%+ |

**为什么叫"一次性沉没成本"**：ef_construction 只在建图时被调用，建完后这个参数就从舞台上消失了。后续所有查询只依赖已建好的图结构（m=16 的连边关系），不再需要"考察 64 个候选"——路已修好，按路牌走就行。

### 3.4 ef_search = 40：查询时的搜索深度

ef_search 是查询时的"ef_construction"——控制搜索时维护多大的候选池。和 ef_construction 原理完全一样，只是作用的阶段不同。

```
ef_search = 10：                    ef_search = 40（pgvector 默认）：

每到一个节点，看它的 m=16 个邻居    每到一个节点，看它的 m=16 个邻居
候选池最多保留 10 个                 候选池最多保留 40 个

  ┌────────────┐                    ┌──────────────────────────┐
  │ A(0.12)    │                    │ A(0.12) C(0.18) E(0.21)  │
  │ C(0.18)    │                    │ G(0.25) B(0.31) H(0.35)  │
  │ E(0.21)    │ ← 满了             │ D(0.40) J(0.44) F(0.49)  │
  │ G(0.25)    │                    │ K(0.52) L(0.55) M(0.58)  │
  │ B(0.31)    │                    │ ... 共 40 个              │
  │ H(0.35)    │                    └──────────────────────────┘
  │ D(0.40)    │                              ↓
  │ J(0.44)    │                    搜完后从 40 个取 topK=3
  │ F(0.49)    │                    → D, A, G（真正最近的 3 个）
  │ K(0.52)    │
  └────────────┘
         ↓
  搜完后从 10 个取 topK=3
  → A, C, E

  但外面可能还有更近的点没进池！
```

**ef_search 太小 → 候选池很快满 → 真正近的点被挤出池外 → 漏检，召回率低**

**ef_search 太大 → 候选池很大 → 每个点都要和池里的比距离 → 计算量大，查询慢**

**必须满足的约束**：`ef_search ≥ topK`。topK=3 时 ef_search=2 没意义——候选池比你要的结果数还小，拿什么返回？

### 3.5 索引和向量表的配合关系

**建图和存向量，是两件事。** 建 HNSW 索引时，pgvector 做的事：

```
                          向量表（vector_store）
┌─────────────────────────────────────────────────────────┐
│ id  │ content          │ embedding                      │
├─────┼──────────────────┼────────────────────────────────┤
│ 1   │ "HashMap底层..." │ [0.023, -0.451, ..., 0.891]   │ ← 1024 维浮点数
│ 2   │ "STAR法则..."    │ [-0.312, 0.723, ..., -0.156]  │
│ 3   │ "简历优化..."    │ [0.567, -0.234, ..., 0.432]   │
│ ... │ ...              │ ...                            │
└─────────────────────────────────────────────────────────┘
         ↑ 向量实实在在地存在这里，每一行是一条记录

                           HNSW 索引（独立的索引结构）
┌─────────────────────────────────────────────────────────┐
│  不存向量内容，只存 "哪个 id 和哪个 id 是邻居"              │
│                                                         │
│  节点 1 的邻居: [3, 7, 15, ...]  ← 存的是主键 id         │
│  节点 2 的邻居: [5, 8, 12, ...]                         │
│  节点 3 的邻居: [1, 4, 9, ...]                          │
│  ...                                                    │
└─────────────────────────────────────────────────────────┘
         ↑ 索引只存"邻居关系"，不存向量本身
```

**索引建完，向量还在原来的表里。索引是"附加结构"，不替代原数据。**

检索时每一步都是：**索引给邻居 id → 回表读向量 → 算距离 → 挑最近的 → 继续**。

完整流程：

```
用户: "HashMap 底层原理"
  ↓
Embedding API → Q = [-0.023, 0.891, ...]
  ↓
┌──────────────┐      ┌──────────────────┐
│  HNSW 索引    │      │  vector_store 表  │
│  (邻居关系)   │      │  (向量 + 内容)    │
└──────┬───────┘      └────────┬─────────┘
       │                       │
 ① 给入口点 id ────────────────→│
 ② 要邻居列表 ──→ 返回 [3,15]   │
 ③ 给我 id=3 的向量 ──────────→│ 返回 [0.567,...]
 ④ 给我 id=15 的向量 ─────────→│ 返回 [-0.312,...]
 ⑤ 算距离: 15 最近, 去 15       │
 ⑥ 要 id=15 的邻居 ─→ 返回 [2,8]│
 ⑦ 给我 id=2 的向量 ──────────→│ ...
... 反复，直到收敛 ...
       ↓
最终: {id=15(0.31), id=3(0.35), id=8(0.39)}
  ↓
从表里读这三行的 content → 返回给 Spring AI
  ↓
拼入 Prompt → LLM 生成回答
```

**三个关键理解**：

1. **索引是"路牌"，向量是"房子"**。HNSW 索引 = 小区路牌系统（告诉你"往前走左转"），向量 = 每个房子的坐标（用来算"离你多远"）。路牌不存房子长什么样，但能指引方向。到每个路口，你还是要实地看房子的位置算距离。

2. **为什么不全表扫**。无索引 → 10 万条向量 → 10 万次距离计算 → 几百 ms。有索引 → 每层只看 m=16 个邻居 × 3 层 ≈ 只看几十个 → 几 ms。图帮你跳过了 99.9% 不可能近的点。

3. **索引只存邻居 id，不存向量**。向量本身已在表里，再存一份浪费空间。索引的职责是**导航**，不是**存储**。就像 GPS 导航仪不需要把每家每户的照片存进去，只需要路网结构就能导到目的地。

### 3.6 三类参数的完整对比

```
建图阶段（离线，一次性）         查询阶段（在线，每次搜索）
┌──────────────────────┐       ┌──────────────────────┐
│ m=16                 │       │ m=16 ← 图结构固定    │
│ ef_construction=64   │  →    │ ef_search=40         │
│ 决定"图的质量上限"    │       │ 决定"实际发挥几成"   │
└──────────────────────┘       └──────────────────────┘
```

| 参数 | 什么时候用 | 控制什么 | 太小 | 太大 | 项目配置 |
|------|-----------|---------|------|------|---------|
| m=16 | 建图时 | 每个点连多少邻居（图密度） | 路网稀疏，查询慢 | 内存爆炸 | 默认 16 |
| ef_construction=64 | 建图时 | 找邻居时考察多少候选 | 连边质量差，召回低 | 建索引慢 | 默认 64 |
| ef_search=40 | 每次查询 | 搜索时候选池多大 | 漏检，召回低 | 查询慢 | 默认 40（未显式配置） |

**三者的递进关系**：

> m 决定图的**骨架密度**（路网多密）→ ef_construction 决定骨架的**搭建质量**（路修得好不好）→ ef_search 决定走在上面时的**搜索深度**（你愿意多仔细地找）

**开快递网点类比**：

| 参数 | 类比 | 含义 |
|------|-----|------|
| m=16 | 每个网点对接 16 个其他网点 | 路网密度，决定查询速度上限 |
| ef_construction=64 | 选址时考察了 64 个备选点 | 图质量，只影响建索引的时间 |
| ef_search=40 | 送快递时每到一个网点查 40 个方向 | 查询精细度，影响每次搜索延迟 |

项目里前两个有配置（`PgVectorConfig` 中由 pgvector 默认值决定），ef_search 用 pgvector 默认值 40——对于 topK=3 的场景绰绰有余。如果未来 topK 调到 20+，ef_search 也要跟调，否则候选池不够装。

---

## 4. ETL Pipeline 完整链路

代码在 `KnowledgeBaseInitializerConfig.java:48-119`。实现了 `CommandLineRunner`——所有 Bean 就绪后自动执行，不阻塞应用启动。

```
classpath:/document/*.md
  → TikaDocumentReader 读取
  → 正则清洗（控制字符、空白行、页码、符号）
  → TokenTextSplitter 切分（500 token/块）
  → KeywordMetadataEnricher（AI 提取 5 个关键词）
  → 分批写入 PGVector（10 条/批）
```

### 4.1 Extract — Tika 通用文档读取

```java
TikaDocumentReader reader = new TikaDocumentReader(resource);
List<Document> docs = reader.read();
```

为什么用 Tika？知识库可能混着 Markdown、PDF、DOCX、HTML。Tika 一个 API 全兼容——内部自动检测 MIME 类型选择对应解析器。当前只有 MD，但架构上不限制后续扩格式。

### 4.2 Clean — 文本清洗（工程重点）

```java
// KnowledgeBaseInitializerConfig.java:126-142
private String cleanText(String raw) {
    // 1. 去除控制字符（保留换行和制表符）
    cleaned = CONTROL_CHARS.matcher(raw).replaceAll("");
    // 2. 3 个以上连续换行合并为 2 个
    cleaned = MULTI_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
    // 3. 去除孤立页码行（PDF 页眉页脚）
    cleaned = PAGE_NUMBER.matcher(cleaned).replaceAll("");
    // 4. 统一列表符号为 "- "
    cleaned = BULLET_SYMBOLS.matcher(cleaned).replaceAll("-");
    return cleaned.trim();
}
```

**为什么这一步很重要**：PDF 提取出的文本带大量噪声——页眉页码、多余空行、不可见控制字符。不洗直接 Embedding，噪声也会被向量化，降低检索精度。**数据质量比模型选择影响更大。**

### 4.3 Splitter — TokenTextSplitter

```java
TokenTextSplitter splitter = new TokenTextSplitter(
        500,  // 每块目标 500 token
        100,  // 最小 100 字符
        5,    // 最短 5 字符才 embed
        200,  // 最多 200 块
        true  // 保留分隔符
);
```

**为什么按 token 而不是字符切？** Embedding 的计费/限制单位是 token。500 token 保证每块语义完整 + 不超 API 限制。

### 4.4 Enrich — AI 提取关键词

```java
KeywordMetadataEnricher keywordEnricher = new KeywordMetadataEnricher(chatModel, 5);
List<Document> enriched = keywordEnricher.apply(chunks);
// → metadata["excerpt_keywords"] = "魅力提升,异性结识,社交技巧,单身,自我提升"
```

**为什么需要**：embedding 向量做语义检索 + metadata 关键词做精确匹配，语义 + 关键词双路互补。

### 4.5 Write — 分批写入（两层保护）

```java
// 外层：按条数分批
int batchSize = 10;
for (int i = 0; i < enriched.size(); i += batchSize) {
    vectorStore.add(enriched.subList(i, Math.min(i + batchSize, enriched.size())));
}
```

```java
// 内层：按 token 数分批（PgVectorConfig.java:31）
.batchingStrategy(new TokenCountBatchingStrategy(
        EncodingType.CL100K_BASE,
        800,   // TokenTextSplitter 500 → 留余量
        0.1
))
```

**为什么需要两层**：DashScope Embedding API 单次限制最多 10 条。如果某条文本超长（切分失败或特殊字符导致），`TokenCountBatchingStrategy` 按 800 token 再次拆分。小数据量看不出，上百篇 PDF 就体现出价值。

---

## 5. 检索链路：配置怎么跑起来的

`ChatClientFactory.java:67-72` — SINGLE 策略的检索：

```java
QuestionAnswerAdvisor.builder(vectorStore)
    .searchRequest(SearchRequest.builder()
            .similarityThreshold(0.5)   // COSINE 相似度阈值
            .topK(3)                    // 返回 top 3
            .build())
    .build()
```

---

## 6. 面试回答模板

### 面试官："你们怎么用 PostgreSQL 做向量检索的？"

> "基于 PostgreSQL + pgvector 扩展。选型上向量和业务数据共库省运维。配置上三个关键决策：1024 维对应 DashScope text-embedding-v4 模型的输出维度；COSINE_DISTANCE 做文本语义匹配——文本嵌入重方向不重大小；HNSW 索引做近似搜索——面试场景查询优先，用图结构的内存开销换 ms 级延迟。ETL 走 Tika 读取 → 正则清洗 → 按 500 token 切片 → AI 提取关键词 → 10 条/批写入 + TokenCountBatchingStrategy 按 800 token 防超限。"

### 面试官追问："HNSW 的 m 和 ef_construction 是什么？"

> "m 是图密度——每个节点最多连多少个邻居，m 越大路网越密查询越快但内存越大，默认 16。ef_construction 是建图时的搜索宽度——决定插入每个节点时考察多少个候选来挑最优的 16 个邻居。它的目的就是让连边质量最大化：候选池越大，越能保证 X 连到真正最近的 16 个点，而不是随便找 16 个了事。但它只影响建索引的时间，建完后查询不受影响——属于一次性沉没成本。另外查询时还有一个 ef_search 参数，和 ef_construction 原理一样，控制搜索时候选池大小——太小漏检，太大查得慢，默认 40。三者递进：m 定骨架密度，ef_construction 定骨架质量，ef_search 定查得多仔细。"

### 面试官追问："索引和向量本身怎么配合工作的？"

> "建图和存向量是两件事。索引只存邻居关系——'节点 1 的邻居是 3、7、15'——不存向量值。向量还在原来的表里。检索时每一步都是：索引告诉你该看哪个邻居 id → 回表读那个 id 的向量 → 算和查询向量的距离 → 挑最近的 → 继续。相当于路牌系统和房子坐标的分工：路牌指引方向，但判断'离你多远'必须看房子的实际坐标。这样不用全表扫——10 万条向量只看几十个就能定位到最近的。"
