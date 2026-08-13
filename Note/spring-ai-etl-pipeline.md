# Spring AI ETL Pipeline 笔记

> 官方文档：https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
> 项目实战：`KnowledgeBaseInitializerConfig.java`（config/）

---

## 1. 什么是 ETL Pipeline

### 1.1 问题背景

RAG 的第一步是把原始文档变成向量库里可检索的条目。原始文档格式五花八门（PDF、Markdown、JSON、HTML、Office…），需要一条流水线统一处理：

```
原始文件 → 读出来 → 切碎 → 写入向量库
```

Spring AI 把这个过程抽象成三个函数式接口，分别对应三个阶段。

### 1.2 为什么用函数式接口

| ETL 阶段 | Spring AI 接口 | Java 函数式接口 | 输入 | 输出 | 核心方法 |
|---|---|---|---|---|---|
| Extract | `DocumentReader` | `Supplier<List<Document>>` | 无 | `List<Document>` | `get()` |
| Transform | `DocumentTransformer` | `Function<List<Document>, List<Document>>` | `List<Document>` | `List<Document>` | `apply()` |
| Load | `DocumentWriter` | `Consumer<List<Document>>` | `List<Document>` | 无 | `accept()` |

这样设计的好处：换不同 Reader 或 Writer，中间的 Transformer 链不用改。今天读 Markdown，明天读 PDF，切分和写入逻辑完全复用。

**类比**：Reader 是供应商（给你货不问你要什么），Transformer 是加工机器（货进、加工、货出），Writer 是消费者（吃掉货、不吐东西）。

---

## 2. Document — 贯穿全链路的数据载体

反编译自 `spring-ai-commons-1.1.2.jar`：

```java
public class Document {
    // 核心字段（private，通过 getter 访问）
    // - id: String        → UUID 唯一标识
    // - text: String      → 文本内容（检索命中后拼入 Prompt 喂给 LLM 的部分）
    // - media: Media      → 图片/音频等非文本内容
    // - metadata: Map     → 键值对标签（检索过滤/排序用，LLM 看不到）
    // - score: Double     → 检索时的相似度分数

    public Document(String text)
    public Document(String text, Map<String, Object> metadata)

    public static Document.Builder builder()  // 链式构建
    public String getText()
    public Map<String, Object> getMetadata()
    public Double getScore()
}
```

一条 Document 的实际结构（项目示例）：

```
Document {
    id: "a3f8c2b1-..."
    text: "#### 如何提升自身魅力？\n提升自身魅力需从多方面入手..."
    metadata: {
        "category": "单身",
        "excerpt_keywords": "魅力提升,异性结识,社交技巧,单身,自我提升",
        "source": "恋爱常见问题和回答 - 单身篇.md"
    }
    score: 0.0   // 检索时填充
}
```

**text vs metadata 去向**：text → 拼进 Prompt 喂给 LLM；metadata → 检索时过滤和排序，LLM 看不到。

---

## 3. DocumentReader — Extract（数据提取）

### 3.1 接口定义

```java
// spring-ai-commons
public interface DocumentReader extends Supplier<List<Document>> {
    default List<Document> read() { return get(); }  // 语义别名
}
```

所有 Reader 都是 `implements DocumentReader` 的完整实现，开箱即用。

### 3.2 内置实现速查

| Reader | 输入格式 | 切分方式 | Maven 依赖 |
|---|---|---|---|
| `TextReader` | 纯文本 | 整个文件 = 1 个 Document | core 自带 |
| `JsonReader` | JSON | 数组每个元素 = 1 个 Document | core 自带 |
| `MarkdownDocumentReader` | .md | 按水平线 `---` / 代码块边界 | `spring-ai-markdown-document-reader` |
| `PagePdfDocumentReader` | PDF | 每页 = 1 个 Document | `spring-ai-pdf-document-reader` |
| `ParagraphPdfDocumentReader` | PDF | 按 PDF 目录结构分段 | `spring-ai-pdf-document-reader` |
| `JsoupDocumentReader` | HTML | 按 CSS 选择器 + 元素边界 | `spring-ai-jsoup-document-reader` |
| `TikaDocumentReader` | 通用（PDF/DOCX/PPTX等几十种） | 按 Tika 解析结构 | `spring-ai-tika-document-reader` |

### 3.3 MarkdownDocumentReader 源码

反编译自 `spring-ai-markdown-document-reader-1.0.0-M6.jar`（项目已有依赖）：

```java
public class MarkdownDocumentReader implements DocumentReader {

    public MarkdownDocumentReader(String resourcePath)       // 简单版：默认配置
    public MarkdownDocumentReader(Resource resource,          // 完整版：自定义配置
                                   MarkdownDocumentReaderConfig config)
    public List<Document> get() { ... }                      // 核心方法
}
```

**配置类**控制切分行为：

```java
public class MarkdownDocumentReaderConfig {
    public final boolean horizontalRuleCreateDocument;   // true: --- 处切为新 Document
    public final boolean includeCodeBlock;               // true: 代码块独立成 Document
    public final boolean includeBlockquote;              // true: 引用块独立成 Document
    public final Map<String, Object> additionalMetadata; // 统一加到所有 Document 的 metadata
}
```

**为什么项目没用它而是手写切分逻辑**：`MarkdownDocumentReader` 只认水平线和代码块，不认 `####` 标题。项目的知识库是"一问一答"结构，每个 `####` 一个完整问答——用内置的切不对，手写正则 `(?=#### )` 更精确。

### 3.4 使用示例

```java
// 简单文本
TextReader reader = new TextReader(new ClassPathResource("data.txt"));
reader.getCustomMetadata().put("source", "data.txt");  // 给所有文档打标签
List<Document> docs = reader.read();

// Markdown
MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
    .withHorizontalRuleCreateDocument(true)   // --- 作为 Document 边界
    .withAdditionalMetadata("category", "技术文档")
    .build();
List<Document> docs = new MarkdownDocumentReader(resource, config).read();

// PDF 按页
PagePdfDocumentReader reader = new PagePdfDocumentReader(
    "classpath:report.pdf",
    PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
List<Document> docs = reader.read();
```

### 3.5 自定义 Reader

当内置 Reader 覆盖不了你的格式时（比如 CSV），自己实现接口只重写一个方法：

```java
public class CsvDocumentReader implements DocumentReader {
    private final Resource resource;

    @Override
    public List<Document> get() {
        List<Document> docs = new ArrayList<>();
        for (String[] row : parseCsv(resource)) {
            docs.add(new Document(row[0], Map.of("source", "csv")));
        }
        return docs;
    }
}
```

---

## 4. DocumentTransformer — Transform（文档转换）

### 4.1 接口定义

```java
// spring-ai-commons
public interface DocumentTransformer extends Function<List<Document>, List<Document>> {
    default List<Document> transform(List<Document> docs) { return apply(docs); }
}
```

输入输出同类，可以无限堆叠：`enricher.apply(splitter.apply(docs))`。

### 4.2 TokenTextSplitter — 按 Token 切分

反编译自 `spring-ai-commons-1.1.2.jar`：

```java
// 父类 TextSplitter：模板方法模式
public abstract class TextSplitter implements DocumentTransformer {

    // 遍历每个 Document，调子类的 splitText() 切分，metadata 自动继承
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            result.addAll(split(doc));     // 每个单独切
        }
        return result;
    }

    public List<Document> split(Document document) {
        List<String> chunks = splitText(document.getText());  // ← 子类实现
        List<Document> result = new ArrayList<>();
        for (String chunk : chunks) {
            result.add(new Document(chunk, document.getMetadata()));  // metadata 继承
        }
        return result;
    }

    protected abstract List<String> splitText(String text);  // 子类只需实现这一个
}

// 子类：按 token 数切分
public class TokenTextSplitter extends TextSplitter {

    // 默认参数：chunkSize=800, minChunkSizeChars=350,
    //          minChunkLengthToEmbed=5, maxNumChunks=10000, keepSeparator=true

    protected List<String> splitText(String text) {
        // 1. CL100K_BASE 编码 text → List<Integer> tokenIds
        // 2. token 数 ≤ chunkSize → 不分，直接返回 [原文本]
        // 3. token 数 > chunkSize → 在 minChunkSizeChars 后找标点断开
        // 4. 循环直到整个文本切完
        return doSplit(text, chunkSize);
    }
}
```

**CL100K_BASE** 是 OpenAI 定义的编码规则，把文本转成 token ID：

```
"怎么追女生" → [62341, 243, 14632, 119, 38493]  ← 5 token
"how to date" → [4438, 284, 2524]               ← 3 token
```

**为什么按 token 切而不是按字数切**：模型的上下文窗口按 token 计算，不是按字数。800 token ≈ 中文 400-600 字。

**为什么在小文本（≤ chunkSize）不按标点切**：短文本本身语义完整，再按标点切会碎片化。这是一个 v2.0+ 的优化。

### 4.3 自定义 Transformer

想按自己的规则切分（比如段落、自定义分隔符），只需继承 `TextSplitter` 重写一个 `splitText()`：

```java
public class HeadingSplitter extends TextSplitter {
    @Override
    protected List<String> splitText(String text) {
        return Arrays.asList(text.split("(?=#### )"));  // 按四级标题切
    }
}
// 父类的 apply() 遍历 / metadata 继承 / Document 组装全自动
```

### 4.4 为什么 metadata 要继承

原始 Document 的 metadata 有"来源文件"、"分类"等信息。切成小块后，每个小块也必须带这些标签，否则检索时过滤条件失效。

### 4.5 自定义标点符号

```java
// 中文场景
TokenTextSplitter.builder()
    .withChunkSize(800)
    .withPunctuationMarks(List.of('。', '？', '！', '；', '，'))
    .build();
```

---

## 5. MetadataEnricher — 元数据增强器

两个 Enricher 都实现 `DocumentTransformer`，不切分文档，只往 metadata 里加信息。

### 5.1 KeywordMetadataEnricher

反编译自 `spring-ai-model-1.1.2.jar`：

```java
public class KeywordMetadataEnricher implements DocumentTransformer {

    // 常量
    public static final String EXCERPT_KEYWORDS_METADATA_KEY = "excerpt_keywords";
    public static final String CONTEXT_STR_PLACEHOLDER = "context_str";
    public static final String KEYWORDS_TEMPLATE =
        "{context_str}. Give %s unique keywords for this document. Format as comma separated. Keywords:";

    // 构造：需要 ChatModel 调 LLM
    public KeywordMetadataEnricher(ChatModel chatModel, int keywordCount)

    // 每条 Document 调一次 LLM，结果塞入 metadata["excerpt_keywords"]
    public List<Document> apply(List<Document> documents) { ... }
}
```

### 5.2 项目使用方式

```java
// KnowledgeBaseInitializerConfig.java 实际代码
KeywordMetadataEnricher keywordEnricher = new KeywordMetadataEnricher(chatModel, 5);

// 注意：包路径是 org.springframework.ai.model.transformer.KeywordMetadataEnricher
// 不是 org.springframework.ai.transformer.KeywordMetadataEnricher

List<Document> enriched = keywordEnricher.apply(chunks);
// 读取关键词：
enriched.get(i).getMetadata().get(KeywordMetadataEnricher.EXCERPT_KEYWORDS_METADATA_KEY);
```

### 5.3 实际运行效果

```
chunk[0] keywords: 魅力提升,异性结识,社交技巧,单身,自我提升
chunk[1] keywords: 社交场合,线上交友,个人资料,真诚交流,隐私保护
chunk[2] keywords: 相亲判断,深入发展标准,恋爱焦虑缓解,线上交友脱单,两性关系指导
chunk[3] keywords: 价值观契合,情绪稳定性,尊重他人,人生规划,相亲评估
```

### 5.4 SummaryMetadataEnricher

```java
public class SummaryMetadataEnricher implements DocumentTransformer {

    public SummaryMetadataEnricher(
        ChatModel chatModel,
        List<SummaryMetadatEnricher.SummaryType> summaryTypes,  // PREVIOUS/CURRENT/NEXT
        String summaryTemplate,     // 自定义摘要模板
        MetadataMode metadataMode   // 元数据处理方式
    )
}
```

使用：

```java
SummaryMetadataEnricher enricher = new SummaryMetadataEnricher(
    chatModel,
    List.of(SummaryType.PREVIOUS, SummaryType.CURRENT, SummaryType.NEXT)
);
// metadata 多了三条:
//   "section_summary"      → 当前段摘要
//   "prev_section_summary" → 前一段摘要
//   "next_section_summary" → 后一段摘要
```

**为什么需要前后文摘要**：RAG 检索命中某个 chunk 时，LLM 不知道相邻 chunk 在说什么。把前/后段的摘要挂上，LLM 能理解上下文，回答更连贯。

### 5.5 Enricher 对比

| | TokenTextSplitter | KeywordMetadataEnricher | SummaryMetadataEnricher |
|---|---|---|---|
| 类型 | 规则型（纯算法） | AI 增强型（调 LLM） | AI 增强型（调 LLM） |
| 改了什么 | 拆分 text | 不改 text，只加 metadata | 不改 text，只加 metadata |
| 成本 | 0（纯 CPU） | 每条一次 LLM 调用 | 每条三次 LLM 调用 |
| 何时用 | 必须（文档太长） | 可选（检索精度不够时） | 可选（回答需要上下文时） |

### 5.6 对项目的意义

关键词增强对 **PGVector** 有效——metadata 完整保留，检索时可辅助过滤。项目当前已集成 `KeywordMetadataEnricher`，每个 chunk 自动生成 5 个关键词存入 `metadata["excerpt_keywords"]`。

对**云知识库**（DashScopeCloudStore）无效——`upsertPipeline` 上传时不带自定义 metadata，关键词会丢失。

---

## 6. DocumentWriter — Load（数据写入）

### 6.1 接口定义

```java
// spring-ai-commons
public interface DocumentWriter extends Consumer<List<Document>> {
    default void write(List<Document> docs) { accept(docs); }
}
```

### 6.2 内置实现

**VectorStore**（你项目用的）：`VectorStore` 继承了 `DocumentWriter`，三种写法等价：

```java
vectorStore.accept(documents);   // Consumer 风格
vectorStore.add(documents);      // VectorStore 风格
vectorStore.write(documents);    // DocumentWriter 风格
```

**FileDocumentWriter**：输出到文本文件，调试用：

```java
FileDocumentWriter writer = new FileDocumentWriter(
    "output.txt",        // 输出文件
    true,                // 加 ### Doc: N 标记
    MetadataMode.ALL,    // metadata 也写进去
    false);              // 不追加，覆盖
writer.accept(documents);
```

---

## 7. 完整 ETL 链

### 7.1 代码示例

```java
@Component
public class DocumentIngestionPipeline {

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public void ingest(Resource resource) {
        // 1. Extract: 读取文档
        List<Document> docs = new MarkdownDocumentReader(resource, config).read();

        // 2. Transform: 切分
        List<Document> chunks = TokenTextSplitter.builder()
            .withChunkSize(800)
            .build()
            .apply(docs);

        // 3. Transform: 关键词增强（可选）
        List<Document> enriched = KeywordMetadataEnricher.builder(chatModel)
            .keywordCount(5)
            .build()
            .apply(chunks);

        // 4. Load: 写入向量库
        vectorStore.accept(enriched);
    }
}
```

### 7.2 链式调用等价写法

```java
// 函数式
vectorStore.accept(enricher.apply(splitter.apply(reader.get())));

// 语义式
vectorStore.write(enricher.transform(splitter.transform(reader.read())));

// 分步式
List<Document> docs = reader.read();
List<Document> chunks = splitter.apply(docs);
List<Document> enriched = enricher.apply(chunks);
vectorStore.accept(enriched);
```

### 7.3 执行顺序

必须是**先切后增强**。先增强后切会导致：对大段的泛化关键词（"恋爱、婚姻"）没有检索区分度，对切碎后的小段提取的才是精准词（"魅力提升、外在形象"）。

---

## 8. 项目实际做法 vs 标准 ETL

| ETL 阶段 | 标准 Spring AI 方式 | 项目当前做法 |
|---|---|---|
| **Extract** | `MarkdownDocumentReader` 按水平线切 | `ResourcePatternResolver` + `getContentAsString()` |
| **Transform（切分）** | `TokenTextSplitter` 按 token 切 | `TokenTextSplitter`（chunkSize=500, 标准 API） |
| **Transform（增强）** | `KeywordMetadataEnricher` LLM 提取关键词 | `KeywordMetadataEnricher`（keywordCount=5, 标准 API）|
| **Metadata（分类）** | — | 手写 `extractCategory()` 正则匹配标题（已婚/单身/恋爱）|
| **Load** | `vectorStore.accept()` | `vectorStore.add()`（同一接口） |

### 8.1 完整 ETL 流程（KnowledgeBaseInitializerConfig）

```java
// 1. Extract: 读取 classpath:/document/*.md
Resource[] resources = resourceLoader.getResources("classpath:/document/*.md");
for (Resource resource : resources) {
    String content = resource.getContentAsString(StandardCharsets.UTF_8);
    String category = extractCategory(content);  // 正则提取分类

    Document rawDoc = Document.builder()
        .text(content)
        .metadata(Map.of("category", category, "source", resource.getFilename()))
        .build();

    // 2. Transform(切分): TokenTextSplitter 按 500 token 切分
    List<Document> chunks = splitter.apply(List.of(rawDoc));

    // 3. Transform(增强): KeywordMetadataEnricher AI 提取 5 个关键词
    //    → 写入 metadata["excerpt_keywords"]
    List<Document> enriched = keywordEnricher.apply(chunks);

    // 4. Load: 写入 PGVector
    vectorStore.add(enriched);
}
```

### 8.2 为什么混合使用标准 API + 手写逻辑

- **TokenTextSplitter + KeywordMetadataEnricher**：标准 API，切分和关键词提取是通用需求，直接用不需要自己造轮子
- **extractCategory()**：手写正则，因为分类逻辑是业务特化的（按标题关键词判断 已婚/单身/恋爱），AI 提取不如规则精确
- **不用 MarkdownDocumentReader**：它只认水平线 `---` 和代码块，不认 `####` 标题边界，切出来粒度不对

### 8.3 元数据两层体系

| 元数据 | 来源 | 粒度 | 值示例 | 用途 |
|--------|------|------|--------|------|
| `category` | `extractCategory()` 正则 | 整篇文档 | `已婚` / `单身` / `恋爱` | 粗筛：按类别过滤 |
| `excerpt_keywords` | `KeywordMetadataEnricher` AI | 每个 chunk | `魅力提升,异性结识,社交技巧` | 细查：定位具体话题 |
| `source` | 文件名 | 整篇文档 | `恋爱常见问题和回答 - 单身篇.md` | 溯源 |

---

## 9. 核心对象速查

| 对象 | 接口 | 职责 | 是否现成 |
|---|---|---|---|
| `Document` | — | 数据载体（text + metadata + score） | 直接用 |
| `DocumentReader` | `Supplier<List<Document>>` | 读文件 → Document 列表 | 80% 内置，特殊格式自己写 |
| `TokenTextSplitter` | `DocumentTransformer` | 按 token 切分 | 直接用 |
| `TextSplitter`（父类） | `DocumentTransformer` | 自定义切分时继承 | 重写一个 `splitText()` |
| `KeywordMetadataEnricher` | `DocumentTransformer` | LLM 提取关键词到 metadata | 直接用（需 ChatModel） |
| `SummaryMetadataEnricher` | `DocumentTransformer` | LLM 生成摘要到 metadata | 直接用（需 ChatModel） |
| `VectorStore` | `DocumentWriter` | 写入向量库 | 直接用 |
| `FileDocumentWriter` | `DocumentWriter` | 写入文件（调试） | 直接用 |

---

## 10. 常见问题

**Q: 如何自定义切分逻辑？**
继承 `TextSplitter`，只重写 `splitText(String)` 一个方法。父类自动处理遍历、metadata 继承、Document 组装。

**Q: TokenTextSplitter 按 token 切分，为什么还要 minChunkSizeChars？**
防止在小文本上过度切分。如果一个文本只有 200 个字符，即使按 800 token 的 chunkSize 也切不了，minChunkSizeChars=350 进一步保证小块不再被切碎。

**Q: KeywordMetadataEnricher 每条都调 LLM，成本怎么控制？**
只对检索频率高的热点文档做，或者用本地 NLP 方案（TF-IDF 提取关键词）替代 LLM。

**Q: 云知识库能用 Enricher 吗？**
取决于云实现。`DashScopeCloudStore` 上传时不带自定义 metadata，Enricher 无效。`PgVectorStore` 等本地向量库完整保留 metadata。

**Q: KeywordMetadataEnricher 的正确包路径是什么？**
`org.springframework.ai.model.transformer.KeywordMetadataEnricher`（在 `spring-ai-model` jar 中），不是 `org.springframework.ai.transformer.KeywordMetadataEnricher`。

**Q: 自定义 Reader 和自定义 Transformer 的区别？**
Reader 处理**格式**问题（PDF vs Markdown），Transformer 处理**内容**问题（切碎、增强）。读 CSV 用自定义 Reader，按段落切用自定义 Transformer。
