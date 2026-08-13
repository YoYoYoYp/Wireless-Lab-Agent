package com.njupt.wirelesslabagent.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一内置文档和上传文档的知识分类、设备类型与文档类型。
 */
@Component
public class KnowledgeMetadataResolver {

    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final List<String> CATEGORIES = List.of(
            "SDR基础", "USRP设备", "实验流程", "故障诊断", "安全规范"
    );

    public List<String> supportedCategories() {
        return CATEGORIES;
    }

    public KnowledgeMetadata resolve(String filename,
                                     String content,
                                     String requestedCategory,
                                     String requestedDeviceType,
                                     String requestedDocumentType,
                                     String requestedChapter) {
        String category = normalizeCategory(providedValue(requestedCategory));
        if (category == null) {
            category = inferCategory(filename, content);
        }

        String deviceType = providedValue(requestedDeviceType);
        if (deviceType == null) {
            deviceType = inferDeviceType(filename, content);
        }

        String documentType = providedValue(requestedDocumentType);
        if (documentType == null) {
            documentType = switch (category) {
                case "USRP设备" -> "设备手册";
                case "实验流程" -> "实验流程";
                case "故障诊断" -> "故障手册";
                case "安全规范" -> "安全规范";
                default -> "原理知识";
            };
        }

        String chapter = providedValue(requestedChapter);
        if (chapter == null) {
            chapter = category;
        }
        return new KnowledgeMetadata(category, deviceType, documentType, chapter);
    }

    private String inferCategory(String filename, String content) {
        Matcher matcher = FIRST_HEADING.matcher(content == null ? "" : content);
        if (matcher.find()) {
            String headingCategory = normalizeCategory(matcher.group(1));
            if (headingCategory != null) {
                return headingCategory;
            }
        }

        String searchable = searchableText(filename, content);
        if (containsAny(searchable, "安全", "射频防护", "合规", "safety", "compliance")) {
            return "安全规范";
        }
        if (containsAny(searchable, "故障", "诊断", "排查", "overflow", "underflow", "timeout",
                "troubleshoot", "error")) {
            return "故障诊断";
        }
        if (containsAny(searchable, "usrp", "x300", "x310", "2943", "设备手册")) {
            return "USRP设备";
        }
        if (containsAny(searchable, "实验", "流程", "工具", "扫频", "回环", "workflow", "capability")) {
            return "实验流程";
        }
        return "SDR基础";
    }

    private String inferDeviceType(String filename, String content) {
        String searchable = searchableText(filename, content);
        if (containsAny(searchable, "x300", "x310", "2943")) {
            return "USRP X300/N2943R";
        }
        if (searchable.contains("ris")) {
            return "RIS";
        }
        if (searchable.contains("usrp")) {
            return "USRP";
        }
        return "通用SDR";
    }

    private String normalizeCategory(String value) {
        if (value == null) {
            return null;
        }
        if (CATEGORIES.contains(value)) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "安全", "合规", "safety")) return "安全规范";
        if (containsAny(lower, "故障", "诊断", "排查", "troubleshoot", "error")) return "故障诊断";
        if (containsAny(lower, "usrp", "设备")) return "USRP设备";
        if (containsAny(lower, "实验", "流程", "工具", "workflow")) return "实验流程";
        if (containsAny(lower, "sdr", "原理", "调制", "iq")) return "SDR基础";
        return null;
    }

    private String searchableText(String filename, String content) {
        String body = content == null ? "" : content.substring(0, Math.min(content.length(), 8_000));
        return ((filename == null ? "" : filename) + "\n" + body).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String providedValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if ("自动识别".equals(normalized) || "未指定".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    public record KnowledgeMetadata(String category,
                                    String deviceType,
                                    String documentType,
                                    String chapter) {

        public void applyTo(Document document, String source, String owner, String ingestionOrigin) {
            document.getMetadata().put("category", category);
            document.getMetadata().put("source", source == null ? "unknown" : source);
            document.getMetadata().put("device_type", deviceType);
            document.getMetadata().put("document_type", documentType);
            document.getMetadata().put("chapter", chapter);
            document.getMetadata().put("owner", owner == null ? "system" : owner);
            document.getMetadata().put("ingestion_origin", ingestionOrigin);
            document.getMetadata().put("metadata_version", "1");
            document.getMetadata().put("language", "zh-CN");
        }
    }
}
