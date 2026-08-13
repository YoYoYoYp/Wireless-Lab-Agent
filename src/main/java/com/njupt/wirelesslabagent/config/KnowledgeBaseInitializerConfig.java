package com.njupt.wirelesslabagent.config;

import com.njupt.wirelesslabagent.service.KnowledgeMetadataResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Slf4j
public class KnowledgeBaseInitializerConfig implements CommandLineRunner {

    // PDF 常见噪声正则（预编译，避免循环内重复编译）
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern PAGE_NUMBER = Pattern.compile("(?m)^\\s*\\d{1,4}\\s*$");
    private static final Pattern BULLET_SYMBOLS = Pattern.compile("[•●○■□▪▫◆◇►▻]");
    private static final List<String> LEGACY_INTERVIEW_SOURCES = List.of(
            "面试常见问题和回答 - 技术面篇.md",
            "面试常见问题和回答 - 简历篇.md",
            "面试常见问题和回答 - 行为面篇.md"
    );

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourceLoader;
    private final JdbcTemplate jdbcTemplate;
    private final ChatModel chatModel;
    private final KnowledgeMetadataResolver metadataResolver;

    @Value("${knowledge.bootstrap.enabled:true}")
    private boolean bootstrapEnabled;

    @Value("${knowledge.bootstrap.rebuild:false}")
    private boolean rebuildOnStartup;

    @Value("${knowledge.bootstrap.version:2026-08-12-v1}")
    private String bundledKnowledgeVersion;

    public KnowledgeBaseInitializerConfig(VectorStore vectorStore,
                                          ResourcePatternResolver resourceLoader,
                                          JdbcTemplate jdbcTemplate,
                                          ChatModel chatModel,
                                          KnowledgeMetadataResolver metadataResolver) {
        this.vectorStore = vectorStore;
        this.resourceLoader = resourceLoader;
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
        this.metadataResolver = metadataResolver;
    }

    /**
     * ETL Pipeline
     */
    @Override
    public void run(String... args) throws Exception {
        if (!bootstrapEnabled) {
            log.info("Knowledge bootstrap disabled");
            return;
        }
        String ingestionRunId = UUID.randomUUID().toString();
        if (rebuildOnStartup) {
            log.info("Safely rebuilding bundled SDR knowledge; user uploads will be preserved");
        }

        /*
         * ETL 流程：Read(读取) → Transform(切分 → 增强) → Write(写入)
         *  1. TokenTextSplitter         按 token 数切分
         *  2. KeywordMetadataEnricher   AI 提取关键词 → 写入 metadata.excerpt_keywords
         */
        TokenTextSplitter splitter = new TokenTextSplitter(
                500,  // defaultChunkSize: 每块目标 token 数
                100,  // minChunkSizeChars: 每块最小字符数
                5,    // minChunkLengthToEmbed: 最小嵌入长度
                200,  // maxNumChunks: 最大块数（大 PDF 可达 80+ chunk，设 200 防截断）
                true  // keepSeparator: 保留分隔符（换行等）
        );

        // KeywordMetadataEnricher: 调 AI 为每个 chunk 提取 5 个关键词
        KeywordMetadataEnricher keywordEnricher = new KeywordMetadataEnricher(chatModel, 5);
        Resource[] resources = resourceLoader.getResources("classpath:/document/*");
        int totalDocs = 0;

        for (Resource resource : resources) {
            String source = resource.getFilename();
            if (!rebuildOnStartup && bundledSourceIsCurrent(source)) {
                log.info("Bundled knowledge is current; skip source={}", source);
                continue;
            }
            // 1. Extract: Tika 通用文档读取（自动识别格式：PDF/DOCX/HTML/MD 等）
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> docs = new ArrayList<>(reader.read());

            // 1.5 Clean: 清洗 PDF 噪声（控制字符、多余空白、孤立页码）
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                docs.set(i, new Document(cleanText(doc.getText()), doc.getMetadata()));
            }

            // 合并 Tika 返回的多段 Document 为完整文本，用于分类提取
            String fullText = docs.stream()
                    .map(Document::getText)
                    .reduce("", (a, b) -> a + "\n" + b);
            var metadata = metadataResolver.resolve(
                    resource.getFilename(), fullText, null, null, null, null);

            // 2. 设备类型、文档来源、章节等元数据会被切片继承，用于后续过滤与溯源。
            for (Document doc : docs) {
                metadata.applyTo(doc, source, "system", "bundled");
                doc.getMetadata().put("knowledge_version", bundledKnowledgeVersion);
                doc.getMetadata().put("ingestion_run_id", ingestionRunId);
                doc.getMetadata().put("ingestion_complete", false);
            }

            // 3. Transform: 切分 → AI 提取关键词
            List<Document> chunks = splitter.apply(docs);
            List<Document> enriched = keywordEnricher.apply(chunks);
            if (enriched.isEmpty()) {
                throw new IllegalStateException("知识来源未生成任何切片，保留旧版本: " + source);
            }

            // 打印 AI 提取的关键词
            for (int i = 0; i < enriched.size(); i++) {
                Object keywords = enriched.get(i).getMetadata().get(KeywordMetadataEnricher.EXCERPT_KEYWORDS_METADATA_KEY);
                log.info("  chunk[{}] keywords: {}", i, keywords);
            }

            // 4. Write: 分批写入向量库（DashScope Embedding API 限制每批最多 10 条）
            int batchSize = 10;
            for (int i = 0; i < enriched.size(); i += batchSize) {
                int end = Math.min(i + batchSize, enriched.size());
                vectorStore.add(enriched.subList(i, end));
                log.info("  Batch {} : {}-{}/{}", (i / batchSize) + 1, i + 1, end, enriched.size());
            }
            int completed = markIngestionComplete(source, ingestionRunId);
            if (completed != enriched.size()) {
                throw new IllegalStateException("知识来源写入数量不一致: source=" + source
                        + ", expected=" + enriched.size() + ", actual=" + completed);
            }
            int removed = removePreviousBundledSource(source, ingestionRunId);
            if (removed > 0) {
                log.info("Replaced {} previous bundled chunks for source={}", removed, source);
            }
            totalDocs += chunks.size();
            log.info("Loaded {} chunks from {} (category: {})",
                    chunks.size(), resource.getFilename(), metadata.category());
        }
        int legacyRemoved = hasCurrentBundledKnowledge() ? removeLegacyInterviewKnowledge() : 0;
        if (legacyRemoved > 0) {
            log.info("Removed {} legacy interview knowledge chunks after SDR knowledge sync", legacyRemoved);
        }
        log.info("Knowledge base initialized with {} chunks total", totalDocs);
    }

    private boolean bundledSourceIsCurrent(String source) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM vector_store
                WHERE metadata->>'source' = ?
                  AND metadata->>'ingestion_origin' = 'bundled'
                  AND metadata->>'knowledge_version' = ?
                  AND metadata->>'ingestion_complete' = 'true'
                """,
                Long.class,
                source,
                bundledKnowledgeVersion
        );
        return count != null && count > 0;
    }

    private int markIngestionComplete(String source, String ingestionRunId) {
        return jdbcTemplate.update(
                """
                UPDATE vector_store
                SET metadata = jsonb_set(metadata, '{ingestion_complete}', 'true'::jsonb, true)
                WHERE metadata->>'source' = ?
                  AND metadata->>'ingestion_origin' = 'bundled'
                  AND metadata->>'ingestion_run_id' = ?
                """,
                source,
                ingestionRunId
        );
    }

    private int removePreviousBundledSource(String source, String ingestionRunId) {
        return jdbcTemplate.update(
                """
                DELETE FROM vector_store
                WHERE metadata->>'source' = ?
                  AND (
                    (metadata->>'ingestion_origin' = 'bundled'
                      AND metadata->>'ingestion_run_id' IS DISTINCT FROM ?)
                    OR (metadata->>'ingestion_origin' IS NULL AND metadata->>'owner' IS NULL)
                  )
                """,
                source,
                ingestionRunId
        );
    }

    private boolean hasCurrentBundledKnowledge() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM vector_store
                WHERE metadata->>'ingestion_origin' = 'bundled'
                  AND metadata->>'knowledge_version' = ?
                  AND metadata->>'ingestion_complete' = 'true'
                """,
                Long.class,
                bundledKnowledgeVersion
        );
        return count != null && count > 0;
    }

    /**
     * 仅清理重构前无 owner/ingestion_origin 标记的三份面试知识。
     * 此方法位于全部 SDR 文档同步完成之后，避免新知识入库失败时先清空旧库。
     */
    private int removeLegacyInterviewKnowledge() {
        return jdbcTemplate.update(
                """
                DELETE FROM vector_store
                WHERE metadata->>'source' IN (?, ?, ?)
                  AND metadata->>'owner' IS NULL
                  AND metadata->>'ingestion_origin' IS NULL
                """,
                LEGACY_INTERVIEW_SOURCES.toArray()
        );
    }

    /**
     * 清洗 PDF/文档提取文本的常见噪声，提升后续切分和向量检索质量。
     *
     * 处理内容：去除控制字符 → 合并多余空行 → 去除孤立页码 → 统一列表符号
     */
    private String cleanText(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        // 1. 去除控制字符（保留换行和制表符）
        String cleaned = CONTROL_CHARS.matcher(raw).replaceAll("");

        // 2. 3 个以上连续换行合并为 2 个
        cleaned = MULTI_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");

        // 3. 去除孤立页码行（PDF 常见：页眉页脚只含数字）
        cleaned = PAGE_NUMBER.matcher(cleaned).replaceAll("");

        // 4. 统一常用列表符号为 "- "
        cleaned = BULLET_SYMBOLS.matcher(cleaned).replaceAll("-");

        return cleaned.trim();
    }

}
