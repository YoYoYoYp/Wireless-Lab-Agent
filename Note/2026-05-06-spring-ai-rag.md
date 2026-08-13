# Spring AI RAG（检索增强生成）完整笔记

> 项目实战：`RagConfig.java` + `KnowledgeBaseInitializer.java` + `LoveApp.java`
> 官方文档：spring-ai/reference/api/retrieval-augmented-generation.html

---

## 1. 什么是 RAG

### 1.1 问题背景

LLM 有两个天然缺陷：

1. **知识截止**：训练数据有截止日期，不知道训练后发生的事情
2. **幻觉**：遇到不知道的问题，会编造听起来合理但实际不存在的答案

RAG（Retrieval Augmented Generation）解决方式：**先查资料再回答**。

```
无 RAG：
  用户："异地恋怎么维持？"
    → LLM 凭记忆编 → 可能是错的，可能是通用鸡汤

有 RAG：
  用户："异地恋怎么维持？"
    → 从知识库检索《异地恋沟通技巧.md》《信任建立.md》
    → 把文档内容拼进 Prompt："参考资料：xxx \n 请基于资料回答"
    → LLM 基于真实文档回答 → 答案有出处
```

**类比**：闭卷考试（LLM 裸答）vs 开卷考试（RAG）。开卷不是保证满分，但保证不瞎编。

### 1.2 幻觉的本质

LLM 不是数据库，是概率模型。它输出下一个 token 的时候，选的是概率最高的那个，不是"事实最正确"的那个。

```
输入："法国的首都是"
  → LLM 内部：Paris(0.95), London(0.02), Berlin(0.01), ...
  → 输出：Paris ✓（训练数据里有）

输入："我公司 HR 邮箱是"
  → LLM 内部：hr@company.com(0.3), hr@example.com(0.25), ...
  → 输出：hr@company.com ✗（瞎编的，但概率最高）
```

RAG 的做法是在 LLM 输出之前，先给它一份"标准答案池"——你只能在里面找，不能编。

---

## 2. Spring AI 中的两种 RAG 实现

Spring AI 提供两套 API，抽象层次不同：

| | QuestionAnswerAdvisor | RetrievalAugmentationAdvisor |
|---|---|---|
| Maven 依赖 | `spring-ai-advisors-vector-store` | `spring-ai-rag` |
| 设计思路 | 一体式：检索→增强→回答 一个类搞定 | 模块化：每个环节独立组件，可拼装 |
| 灵活性 | 低，只能换模板和过滤条件 | 高，每步可替换实现 |
| 适用 | 简单 RAG，像本项目 | 需要查询改写/重排序的复杂场景 |
| 项目使用 | ✅ | 未使用 |

**为什么有两套？** `QuestionAnswerAdvisor` 是早期的"够用就好"方案。后来社区需要更多定制能力（改写查询、多路检索、重排序），官方出了模块化的 `RetrievalAugmentationAdvisor`。两者项目都依赖了（`spring-ai-advisors-vector-store` 在 pom.xml），但只用了前者。

### 2.1 QuestionAnswerAdvisor 源码级流程

基于反编译的 `QuestionAnswerAdvisor.class`，`before()` 方法做了以下事情：

```java
// 源码还原（反编译 + 逻辑推导）
public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    // Step A: 从 request 中取出用户文本
    String userText = request.prompt().getUserMessage().getText();

    // Step B: 拷贝模板 SearchRequest + 填入动态 query + filter
    SearchRequest searchRequest = SearchRequest.from(this.searchRequest)
        .query(userText)                                              // 动态：用户问题
        .filterExpression(doGetFilterExpression(request.context()))   // 动态：过滤条件
        .build();

    // Step C: 执行向量检索
    List<Document> documents = vectorStore.similaritySearch(searchRequest);

    // Step D: 将 Document.text 拼接成一段字符串
    String context = documents.stream()
        .map(Document::getText)
        .collect(Collectors.joining(System.lineSeparator()));

    // Step E: 用 PromptTemplate 把 query + context 填入模板
    String augmentedText = this.promptTemplate.render(
        Map.of("query", userText,
               "question_answer_context", context));

    // Step F: 把增强后的文本替换回用户消息
    return request.mutate()
        .prompt(request.prompt().augmentUserMessage(augmentedText))
        .build();
}
```

**为什么要拷贝 SearchRequest 而不是修改？** `this.searchRequest` 是构建时配的模板，多线程共用。每次请求拷贝一份再填动态值，线程安全。

### 2.2 RetrievalAugmentationAdvisor 概览

模块化设计，把 RAG 拆成四个可替换阶段：

```
Pre-Retrieval → Retrieval → Post-Retrieval → Generation
  查询预处理      检索文档      文档后处理       生成回答

每个阶段都有多种实现可选：
  Pre-Retrieval:  RewriteQueryTransformer / CompressionQueryTransformer / TranslationQueryTransformer
                  MultiQueryExpander
  Retrieval:      VectorStoreDocumentRetriever
                  ConcatenationDocumentJoiner
  Post-Retrieval: DocumentPostProcessor（重排序/去冗余/压缩）
  Generation:     ContextualQueryAugmenter
```

项目暂未使用，后续升级 RAG 时可以考虑。详情见第 5 节。

---

## 3. RAG 四步详解（以本项目代码串联）

### 总览

```
┌──────────────────────────────────────────────────────────────┐
│  第1步：文档加载与切分                                         │
│  KnowledgeBaseInitializer.run()                              │
│  classpath:/document/*.md → 按 #### 切分 → List<Document>    │
├──────────────────────────────────────────────────────────────┤
│  第2步：向量化与存储                                           │
│  vectorStore.add(documents)                                  │
│  Document.text → EmbeddingModel.embed() → float[] → 存两个 Map │
├──────────────────────────────────────────────────────────────┤
│  第3步：过滤与检索                                             │
│  QuestionAnswerAdvisor.before()                              │
│  用户消息 → 向量化 → 元数据过滤 → 余弦相似度排序 → top-K 截断    │
├──────────────────────────────────────────────────────────────┤
│  第4步：查询增强与生成                                          │
│  PromptTemplate 填入 {query} + {question_answer_context}     │
│  → ChatModel.call() → LLM 基于上下文回答                       │
└──────────────────────────────────────────────────────────────┘
```

---

### 第1步：文档加载与切分

#### 1.1 启动时自动执行

`KnowledgeBaseInitializer` 实现了 `CommandLineRunner`：

```java
@Component
public class KnowledgeBaseInitializer implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        // 扫描 classpath:/document/ 下所有 .md 文件
        Resource[] resources = resourceLoader.getResources("classpath:/document/*.md");
        int totalDocs = 0;

        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            String category = extractCategory(content);         // 提取分类（单身/恋爱/已婚）
            List<Document> documents = splitByHeadings(content, category);  // 按标题切分
            vectorStore.add(documents);                          // 向量化 + 存储
            totalDocs += documents.size();
        }
    }
}
```

**为什么实现 CommandLineRunner 而不是 @PostConstruct？** `@PostConstruct` 在 Bean 初始化阶段执行，此时其他依赖（如 EmbeddingModel）可能还没就绪。`CommandLineRunner` 在整个 ApplicationContext 初始化完成后才执行，保证所有依赖可用。

#### 1.2 分类提取

从文件大标题 `# 恋爱常见问题和回答 - 单身篇` 提取分类名：

```java
private String extractCategory(String content) {
    Matcher matcher = CATEGORY_PATTERN.matcher(content);  // 匹配 "# xxx"
    if (matcher.find()) {
        String title = matcher.group(1).trim();
        if (title.contains("单身")) return "单身";
        if (title.contains("恋爱")) return "恋爱";
        if (title.contains("已婚")) return "已婚";
        return title;
    }
    return "未知";
}
```

**为什么分类要作为元数据？** 后续检索时可以按分类过滤。用户问"怎么追女生"，应只看单身篇，不能把"婚后如何保持新鲜感"带进来。

#### 1.3 标题切分

```java
// (?=#### ) 是零宽正向预查：在 #### 之前切，不消费掉 #### 本身
String[] sections = content.split("(?=#### )");

for (String section : sections) {
    String trimmed = section.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("# ")) continue; // 跳过大标题行

    String title = extractHeading(trimmed);
    documents.add(Document.builder()
            .text(trimmed)                                         // 原文 → 喂 LLM
            .metadata(Map.of("category", category, "title", title))  // 标签 → 过滤
            .build());
}
```

**正则 `(?=#### )` 详解**：

```
输入文本：
  "#### 如何提升魅力？\n提升自身魅力需...\n\n#### 怎样主动结识？\n在社交场合..."

普通切分 split("#### ")：
  ["", "如何提升魅力？\n提升自身魅力需...\n\n", "怎样主动结识？\n在社交场合..."]
  问题：#### 被吃掉了

正向预查 split("(?=#### )")：
  ["", "#### 如何提升魅力？\n提升自身魅力需...\n\n", "#### 怎样主动结识？\n在社交场合..."]
  效果：#### 保留在切分结果里
```

#### 1.4 常见文档切分方式对比

| 方式 | 怎么切 | 优点 | 缺点 | 本项目 |
|------|--------|------|------|--------|
| **固定长度** | 每 N 字一刀 | 简单 | 语义截断，可能从一句话中间切开 | ❌ |
| **递归字符** | 先按段落→句子→字 | LangChain 默认，通用性好 | 不感知文档结构 | ❌ |
| **标题切分** | 按 `#`/`##`/`####` | 每个 chunk 是完整知识点 | 依赖文档有好的标题结构 | ✅ |
| **语义切分** | embedding 算相似度找话题转折点 | 最智能 | 成本高，每两句话间都要算一次 | ❌ |

本项目选标题切分的原因：知识库是"一问一答"结构，`####` 做标题，天然语义边界。

#### 1.5 Document 最终结构

拿单身篇第一条数据为例：

```
Document {
    id: "a3f8c2b1-..."          (Spring AI 自动生成 UUID)
    text: "#### 如何提升自身魅力吸引潜在伴侣？
           提升自身魅力需从多方面入手。外在形象上，保持良好个人卫生...
           推荐课程：[《单身魅力提升全攻略》](https://www.codefather.cn)..."
    metadata: {
        "category": "单身",
        "title": "如何提升自身魅力吸引潜在伴侣？"
    }
    score: 0.0                   (检索时填充)
}
```

**原文 vs 元数据的去向**：

| 字段 | 存入位置 | 检索时用途 | 是否喂给 LLM |
|------|---------|-----------|------------|
| `text` | documentMap | 匹配命中后，拼进 Prompt | ✅ 是 |
| `metadata` | documentMap | 过滤筛选 + 日志标识 | ❌ 否 |

---

### 第2步：向量化与存储

#### 2.1 什么是向量化（Embedding）

把文本的语义"压缩"成一串数字。语义相近的文本，向量在空间中位置也相近。

```
"怎么追女生" →  embedding → [0.2, -0.8, 0.3, 0.1, ...]  ← 1024 维度
"如何追求女孩" → embedding → [0.1, -0.9, 0.4, 0.1, ...]  ← 距离近（相似）
"今天天气不错" → embedding → [0.9, 0.1, -0.7, 0.5, ...]  ← 距离远（不相似）
```

**Embedding 模型 vs Chat 模型**：

| | Embedding Model | Chat Model |
|---|---|---|
| 输入 | 一段文本 | 对话消息列表 |
| 输出 | float[] 向量 | 回复文本 |
| 大小 | 小（几百 MB） | 大（几百 GB） |
| 用途 | 文本→数学表示 | 对话生成 |
| 本项目 | 阿里百炼 embedding 模型 | deepseek-v3.2 |

#### 2.2 SimpleVectorStore 内部结构

`vectorStore.add(documents)` 一行代码，内部做了：

```
Document.text
  ↓
EmbeddingModel.embed(text)        ← 调阿里百炼 embedding API
  ↓
float[] vector = [0.023, -0.451, 0.789, ..., -0.312]  ← 1024 维
  ↓
存入 SimpleVectorStore 的两个 ConcurrentHashMap：

┌─ SimpleVectorStore ──────────────────────────────────────────┐
│                                                              │
│  vectorMap: ConcurrentHashMap<String, float[]>               │
│  ┌──────────────┬──────────────────────────────────────────┐ │
│  │ "a3f8c2..."  │ [0.023, -0.451, 0.789, ..., -0.312]     │ │
│  │ "b7d4e9..."  │ [0.112, 0.334, -0.221, ..., 0.543]      │ │
│  │ "c9f1a6..."  │ [-0.087, 0.543, 0.102, ..., -0.198]     │ │
│  └──────────────┴──────────────────────────────────────────┘ │
│                                                              │
│  documentMap: ConcurrentHashMap<String, Document>            │
│  ┌──────────────┬──────────────────────────────────────────┐ │
│  │ "a3f8c2..."  │ Document{text:"...", metadata:{...}}    │ │
│  │ "b7d4e9..."  │ Document{text:"...", metadata:{...}}    │ │
│  │ "c9f1a6..."  │ Document{text:"...", metadata:{...}}    │ │
│  └──────────────┴──────────────────────────────────────────┘ │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**为什么存两个 Map 而不是一个？**

- `vectorMap`：检索时做数学运算（余弦相似度），只需要 float[]，不需要 Document
- `documentMap`：命中后用 ID 取回原文和元数据，拼 Prompt、打日志
- 分离后检索更快（不用反序列化 Document 对象），是**计算和存储分离**

**为什么用 ConcurrentHashMap？** 支持并发读写。虽然项目启动时一次性写入，但 Spring AI 架构允许运行时动态添加文档。

#### 2.3 向量库配置

```java
// RagConfig.java
@Bean
public VectorStore vectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

**SimpleVectorStore 的局限**：

| 方面 | SimpleVectorStore | RedisVectorStore（生产） |
|------|-------------------|------------------------|
| 存储位置 | JVM 堆内存 | Redis |
| 重启行为 | 清空，需重建 | 持久保留 |
| 多实例 | 各自独立，数据不一致 | 共享同一份，天然一致 |
| 数据量上限 | 受 -Xmx 限制（几百 MB） | 百万级 |
| 适用阶段 | Demo/开发 | 生产 |

---

### 第3步：过滤与检索

#### 3.1 三层筛选漏斗

```
                    SimpleVectorStore 全部文档（~15 条）
                          │
          ┌───────────────┴───────────────┐
          │  第1层：元数据过滤               │  metadata 层面
          │  FILTER_EXPRESSION             │  耗时：O(1) per doc（字符串比较）
          │  "category == '单身'"           │  命中率：~5 条
          └───────────────┬───────────────┘
                          │
          ┌───────────────┴───────────────┐
          │  第2层：向量相似度过滤            │  embedding 层面
          │  similarityThreshold = 0.5     │  耗时：O(n) 余弦计算
          │  低于阈值直接淘汰                  │  命中率：~3 条
          └───────────────┬───────────────┘
                          │
          ┌───────────────┴───────────────┐
          │  第3层：数量截断                  │  Prompt 长度控制
          │  topK = 3                      │  最终返回 ≤ 3 条
          │  按分数排序后取前 N                │
          └─────────────────────────────────┘
```

**为什么按这个顺序？** 元数据过滤成本最低（字符串比较），放最前面减少后续向量计算的数量。向量相似度计算成本高（1024 维浮点运算），只对过滤后的子集算。

#### 3.2 对应代码

**配置层面**（LoveApp 构造函数）：

```java
QuestionAnswerAdvisor.builder(vectorStore)
    .order(1)
    .searchRequest(SearchRequest.builder()
            .similarityThreshold(0.5)   // 向量相似度门槛
            .topK(3)                    // 最多返回 3 条
            .build())
    .build()
```

**运行层面**（doChat 调用时注入分类过滤）：

```java
String filter = detectCategory(message);
.advisors(spec -> {
    spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId);
    if (filter != null) {
        spec.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filter);
    }
})
```

#### 3.3 FILTER_EXPRESSION 原理

`FILTER_EXPRESSION` 是 `QuestionAnswerAdvisor` 中定义的一个字符串常量：

```java
// QuestionAnswerAdvisor 源码
public static final String FILTER_EXPRESSION = "qa_filter_expression";
```

本质是一个约定的 Map key。在 `before()` 中通过 `doGetFilterExpression()` 读取：

```java
// 源码还原
protected Filter.Expression doGetFilterExpression(Map<String, Object> context) {
    // 优先读取请求级参数（运行时 param 传的）
    if (context.containsKey("qa_filter_expression")) {
        String exprStr = context.get("qa_filter_expression").toString();
        if (StringUtils.hasText(exprStr)) {
            return parse(exprStr);  // 解析 "category == '单身'" → FilterExpression 对象
        }
    }
    // 回退到构建时的静态配置
    return this.searchRequest.getFilterExpression();
}
```

**三种注入方式及优先级**：

| 注入方式 | 代码示例 | 生效时机 | 优先级 |
|---------|---------|---------|--------|
| 静态（Builder） | `SearchRequest.builder().filterExpression(...)` | 构建时 | 低（默认值） |
| 动态（Supplier） | `.filterExpression(() -> tenantCtx.getId())` | 每次检索前回调 | 中 |
| 请求级（param） | `.param(FILTER_EXPRESSION, "category == '单身'")` | 这次调用 | **高（覆盖前两者）** |

本项目用请求级，因为每个用户消息的恋爱阶段不同，静态写死无法适配。Supplier 需要维护全局上下文，对于当前场景太重。

#### 3.4 余弦相似度计算

检索时向量库内部做的事情：

```
queryVector = [0.2, 0.8, 0.3, ...]

遍历 vectorMap 中每条文档向量：
  doc1_vector = [0.1, 0.7, 0.3, ...]
    cosine = (0.2×0.1 + 0.8×0.7 + 0.3×0.3 + ...) / (|query| × |doc1|)
          = 点积 / (模长之积)
          = 0.87   ← 越接近 1 越相似

  doc2_vector = [0.9, 0.1, 0.5, ...]
    cosine = 0.19   ← 不相似

  排序：0.87 > 0.61 > 0.34 > 0.23 > 0.19 > 0.11
  过滤：0.34 < 0.5 → 淘汰
  截断：topK=3 → 取前两条（只剩两条过阈值）
```

**为什么用余弦相似度而不是欧氏距离？** 余弦只关心方向不关心长度。"追女生"和"追求女孩"向量方向一致但长度可能不同（字数不同），余弦能正确判断相似，欧氏距离会受长度影响。

#### 3.5 分类推断

```java
private String detectCategory(String message) {
    if (containsAny(message, "结婚", "婚后", "老公", "老婆", "夫妻", "婆媳", "离婚", ...))
        return "category == '已婚'";
    if (containsAny(message, "单身", "追", "相亲", "脱单", "表白", "暗恋", "吸引", ...))
        return "category == '单身'";
    if (containsAny(message, "约会", "吵架", "分手", "异地", "恋爱", "男朋友", "对象", ...))
        return "category == '恋爱'";
    return null;  // 未命中 → 全量检索
}
```

**优先级设计**：已婚优先判断 → 单身 → 恋爱 → null。因为"老公" "老婆" 等词明确指已婚场景，放最前面避免被"恋爱"关键词误匹配。

**为什么用关键词而不是 LLM 分类？** 关键词快（0ms）、成本 0、确定性 100%。LLM 分类慢（几百 ms）、花钱、可能分错。但关键词覆盖有限（"你好"无法分类），所以未命中返回 null 降级为全量检索，不算 bug 是特性。

#### 3.6 完整检索链路示例

```
用户："怎么追女生"
  ↓
detectCategory("怎么追女生")
  → contains "追" → 命中单身关键词 → return "category == '单身'"
  ↓
QuestionAnswerAdvisor.before():
  ├── query = "怎么追女生"
  ├── queryVector = embeddingModel.embed("怎么追女生")
  ├── filterExpression = parse("category == '单身'")
  └── vectorStore.similaritySearch(
          SearchRequest{query: "怎么追女生",
                        filterExpression: "category == '单身'",
                        similarityThreshold: 0.5,
                        topK: 3})
  ↓
SimpleVectorStore.similaritySearch():
  ├── 遍历 vectorMap，先过滤 metadata.category != '单身' 的文档
  ├── 对剩余文档逐一计算余弦相似度
  │     0.87  "如何提升自身魅力吸引潜在伴侣？"     ← 单身篇 #1
  │     0.61  "怎样在社交场合主动结识心仪异性？"    ← 单身篇 #2
  │     0.34  "线上交友有哪些注意事项？"           ← 单身篇 #3 → 低于 0.5，淘汰
  ├── 排序 → 0.87 > 0.61
  └── topK=3 → 只返回 2 条（只有两条过阈值）
  ↓
返回 [Document{title:"如何提升自身魅力..."}, Document{title:"怎样主动结识..."}]
  ↓
进入第4步
```

---

### 第4步：查询增强与生成

#### 4.1 默认模板

`QuestionAnswerAdvisor` 内置的默认模板（项目没自定义，用这个）：

```
{query}

Context information is below.
---------------------
{question_answer_context}
---------------------

Given the context information and no prior knowledge, answer the query.
Follow these rules:
1. If the answer is not in the context, just say that you don't know.
2. Avoid statements like "Based on the context..." or "The provided information...".
```

两个占位符：

| 占位符 | 填入内容 | 来源 |
|--------|---------|------|
| `{query}` | 用户原始问题 | `request.prompt().getUserMessage().getText()` |
| `{question_answer_context}` | 检索到的文档 text 拼接 | `documents.stream().map(Document::getText).collect(joining("\n"))` |

#### 4.2 实际填充效果

```
怎么追女生

Context information is below.
---------------------
#### 如何提升自身魅力吸引潜在伴侣？
提升自身魅力需从多方面入手。外在形象上，保持良好个人卫生，选择适合自己身材与风格的着装
定期锻炼塑造健康体魄。内在修养方面，培养广泛兴趣爱好，如阅读提升知识储备...

#### 怎样在社交场合主动结识心仪异性？
在社交场合，首先要保持微笑，展现亲和力。主动寻找话题切入点，比如在画展上可从对某幅作品的
看法聊起。真诚地表达自己对对方的兴趣...
---------------------

Given the context information and no prior knowledge, answer the query.
Follow these rules:
1. If the answer is not in the context, just say that you don't know.
2. Avoid statements like "Based on the context..." or "The provided information...".
```

#### 4.3 为什么这个模板能减少幻觉

三条指令锁死 LLM 行为：

1. **"answer the query based on context"** → 只能基于资料回答，不能用训练记忆
2. **"if not in context, say you don't know"** → 资料里没有的别编
3. **"avoid 'Based on the context...'"** → 别在回答里暴露"我是在读资料"，用户体验好

**如果用纯拼接不用模板**（`"参考资料：《xxx》" + userQuery`），LLM 可能忽略上下文、编造不相关内容。模板的指令区明确约束了回答边界。

#### 4.4 自定义模板示例（需要时用）

```java
PromptTemplate strictTemplate = PromptTemplate.builder()
    .template("""
        {query}

        [参考资料]
        {question_answer_context}
        [/参考资料]

        规则：
        1. 仅根据参考资料回答，宁可不答也不要编造
        2. 回答中禁止引用"参考资料"这个词
        3. 如果参考资料不包含答案，回复"很抱歉，当前知识库未收录该问题的答案"
        """)
    .build();

QuestionAnswerAdvisor.builder(vectorStore)
    .promptTemplate(strictTemplate)
    .build();
```

---

## 4. Advisor 链中 RAG 的位置

```java
// LoveApp 构造函数
.defaultAdvisors(
    builder(chatMemory).order(0).build(),                              // 记忆
    QuestionAnswerAdvisor.builder(vectorStore).order(1).build(),      // RAG
    new ReReadingAdvisor().withOrder(2),                               // RE2
    new MyLogAdvisor().withOrder(3)                                    // 日志
)
```

**执行时序**：

```
order=0  MessageChatMemoryAdvisor   → before: 注入历史对话
order=1  QuestionAnswerAdvisor      → before: 用干净问题检索 + 拼接文档
order=2  ReReadingAdvisor           → before: 重复问题增强推理
order=3  MyLogAdvisor               → before: 打印最终请求日志
         ───────── 调用 LLM ─────────
order=3  MyLogAdvisor               → after: 打印响应日志
order=2  ReReadingAdvisor           → after: 原样放行
order=1  QuestionAnswerAdvisor      → after: 原样放行（无后置逻辑）
order=0  MessageChatMemoryAdvisor   → after: 保存新消息
```

**为什么 RAG 排在 order=1？** 必须在 RE2 之前执行。RE2 会在用户消息后追加 `"Read the question again: xxx"`，这个噪声会拉低向量检索精度。RAG 先用干净原文检索，RE2 再对增强后的 Prompt 做一遍复读——分工明确，互不干扰。

---

## 5. Advanced RAG 模块详解（当前未使用，供升级参考）

### 5.1 Pre-Retrieval（查询预处理）

三大 QueryTransformer，都依赖 ChatClientBuilder（用 LLM 做变换）：

| 实现 | 功能 | 适用 | 示例 |
|------|------|------|------|
| `CompressionQueryTransformer` | 对话历史 + 追问 → 独立查询 | 多轮对话 | "它的第二大城市是什么？" + 历史 → "丹麦第二大城市" |
| `RewriteQueryTransformer` | 改写模糊查询 | 表述不清 | "搞个跑起来的例子" → "Spring Boot Hello World 示例" |
| `TranslationQueryTransformer` | 翻译为目标语言 | 多语言 | "Hvad er..." → "What is..." |

**为什么需要压缩？** 用户追问 "它的第二大城市是什么？" 中 "它" 指代不明。直接向量检索命中率低。把对话历史压缩进查询——"丹麦第二大城市是什么？"——检索质量大幅提升。

MultiQueryExpander：一条变多条，从不同语义角度检索，提高召回率：

```java
MultiQueryExpander.builder()
    .chatClientBuilder(chatClientBuilder)
    .numberOfQueries(3)
    .build()
    .expand(new Query("How to run a Spring Boot app?"));
// → ["Spring Boot application startup guide",
//    "Running Spring Boot with Maven or Gradle",
//    "How to execute a Spring Boot JAR file"]
```

### 5.2 Retrieval（文档检索）

`VectorStoreDocumentRetriever` — 对标项目目前用的 `QuestionAnswerAdvisor` 内部逻辑，但独立出来可配置。

`ConcatenationDocumentJoiner` — 多路检索结果去重合并，保留首次出现的文档和原始分数。

### 5.3 Post-Retrieval（文档后处理）

检索结果常见问题：

| 问题 | 解决方案 |
|------|---------|
| 前面的文档和问题更相关但排在后面 | 重排序（Re-ranking） |
| 问题 A 和问题 B 的检索结果有重叠 | 去冗余 |
| 检索到的文档太长，上下文爆炸 | 压缩/截断 |

Spring AI 提供 `DocumentPostProcessor` API 可注册到 `RetrievalAugmentationAdvisor`。

### 5.4 Generation（生成）

`ContextualQueryAugmenter` — 对标项目目前 `QuestionAnswerAdvisor` 第 4 步的逻辑。

默认行为：检索不到文档 → 让模型回答 "不知道"。可通过 `allowEmptyContext(true)` 关闭。

**为什么默认不允许空上下文回答？** 空上下文意味着向量库没找到相关知识，此时放行等于白开 RAG——LLM 赤裸面对问题，产生幻觉的概率大幅上升。

---

## 6. 核心对象速查

| 对象 | 所在位置 | 职责 | RAG 步骤 |
|------|---------|------|---------|
| `KnowledgeBaseInitializerConfig` | 项目 `config/` | 启动加载 md → TokenTextSplitter 切分 → KeywordMetadataEnricher AI 提取关键词 → PGVector 存储 | 1、2 |
| `PgVectorConfig` | 项目 `config/` | 创建 PgVectorStore Bean（type=pgvector） | 2 |
| `RagConfig` | 项目 `config/` | 创建 DashScopeCloudStore Bean（type=dashscope） | 2 |
| `KeywordMetadataEnricher` | Spring AI | AI 提取关键词 → `metadata["excerpt_keywords"]` | 2 |
| `PgVectorStore` | Spring AI | PG + pgvector 向量库（持久化，支持 metadata 过滤） | 2、3 |
| `SearchRequest` | Spring AI | 检索参数容器（query/threshold/topK/filter） | 3 |
| `QuestionAnswerAdvisor` | Spring AI | RAG 编排：调检索 + 模板填空 | 3、4 |
| `PromptTemplate` | Spring AI | `{query}` + `{question_answer_context}` 拼合 | 4 |
| `EmbeddingModel`（DashScopeEmbeddingModel） | Spring AI Alibaba | 文本 → float[] 向量（云端 API） | 2、3 |
| `Document` | Spring AI | 文档实体（id + text + metadata + score） | 1~4 |
| `VectorStore` | Spring AI | 向量库接口（统一 PgVector/DashScopeCloud/Redis） | 2、3 |

---

## 7. 常见问题

**Q: 为什么选 PGVector 而不是 SimpleVectorStore？**
SimpleVectorStore 是内存存储，重启数据丢失。PGVector 数据持久化在 PostgreSQL，重启不丢，且支持 metadata 过滤（`filterExpression`），可以按 `category` 或 `excerpt_keywords` 筛选。生产可直接用，不需要额外换 Redis。

**Q: similarityThreshold 设多少合适？**
没有固定值。0.5~0.8 是常见起手值。设太高（0.9）几乎只召回原文完全匹配的，设太低（0.3）噪声多。需要根据实际检索效果调参：观察日志中的命中分数分布，找到长尾截断点。

**Q: 关键词分类太粗糙，用户说"你好"没法分类怎么办？**
三个选择：① 返回 null 全量检索（当前做法，不完美但安全）；② 用 LLM 分类（准确但慢 + 花钱）；③ 给用户提供分类选择按钮（最准但改前端）。项目当前阶段方案①够了。

**Q: QuestionAnswerAdvisor 和 RetrievalAugmentationAdvisor 什么时候切换？**
当需要查询改写、多路检索、重排序等高级能力时切。当前的简单场景不需要切换——一行配置能做到的事不要引入额外复杂度。
