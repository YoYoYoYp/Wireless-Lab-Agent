package com.njupt.wirelesslabagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMetadataResolverTest {

    private final KnowledgeMetadataResolver resolver = new KnowledgeMetadataResolver();

    @Test
    void shouldInferStableCategoryAndMetadataForUploadedTroubleshootingDocument() {
        var metadata = resolver.resolve(
                "uhd-notes.md",
                "# 故障诊断\nRX overflow 表示接收端消费样本不够快。",
                "自动识别",
                "自动识别",
                "自动识别",
                "自动识别"
        );
        Document document = new Document("diagnosis");
        metadata.applyTo(document, "uhd-notes.md", "tester", "upload");

        assertThat(metadata.category()).isEqualTo("故障诊断");
        assertThat(metadata.documentType()).isEqualTo("故障手册");
        assertThat(document.getMetadata())
                .containsEntry("category", "故障诊断")
                .containsEntry("owner", "tester")
                .containsEntry("ingestion_origin", "upload")
                .containsEntry("metadata_version", "1");
    }

    @Test
    void shouldRespectExplicitSupportedCategoryAndInferUsrpDevice() {
        var metadata = resolver.resolve(
                "usrp-2943.pdf",
                "USRP-2943 specifications",
                "安全规范",
                null,
                null,
                null
        );

        assertThat(metadata.category()).isEqualTo("安全规范");
        assertThat(metadata.deviceType()).isEqualTo("USRP X300/N2943R");
        assertThat(metadata.documentType()).isEqualTo("安全规范");
    }
}
