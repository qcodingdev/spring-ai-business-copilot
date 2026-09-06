package dev.qcoding.businesscopilot.supportcopilot.knowledge;

import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocument;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeRetrievalService;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeCopilotSupportKnowledgeRetrieverTest {

    @Test
    void fallsBackToAclScopedAllCategoryRetrievalWhenMappedCategoryHasNoResult() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        KnowledgeDocumentRepository documentRepository = mock(KnowledgeDocumentRepository.class);
        KnowledgeCopilotSupportKnowledgeRetriever retriever =
                new KnowledgeCopilotSupportKnowledgeRetriever(retrievalService, documentRepository);
        SupportKnowledgeQuery query = new SupportKnowledgeQuery(
                "如何将 Acme 报告导出为 CSV？",
                TicketCategory.PRODUCT_USAGE,
                "用户询问报告导出步骤");
        String expectedSearchQuery = "用户询问报告导出步骤 如何将 Acme 报告导出为 CSV？ PRODUCT_USAGE";
        KnowledgeChunk chunk = new KnowledgeChunk(
                17L, 9L, "导出", 0,
                "打开报告，选择导出，再选择 CSV。",
                "打开报告，选择导出", 12, Instant.now());
        RetrievedKnowledgeChunk retrieved = new RetrievedKnowledgeChunk(
                chunk, 0.91d, "test-embedding");
        KnowledgeDocument document = new KnowledgeDocument(
                9L, "Acme 报告手册", "upload", "report.txt",
                "release-smoke", "hash", true, Instant.now(), Instant.now());

        when(retrievalService.retrieve(expectedSearchQuery, "PRODUCT")).thenReturn(List.of());
        when(retrievalService.retrieve(expectedSearchQuery)).thenReturn(List.of(retrieved));
        when(documentRepository.findById(9L)).thenReturn(Optional.of(document));
        when(documentRepository.isRetrievalVisible(9L)).thenReturn(true);

        SupportKnowledgeResult result = retriever.retrieve(query);

        assertThat(result.hasResults()).isTrue();
        assertThat(result.evidence()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.sourceTitle()).isEqualTo("Acme 报告手册");
                    assertThat(evidence.chunkId()).isEqualTo("17");
                });
        verify(retrievalService).retrieve(expectedSearchQuery, "PRODUCT");
        verify(retrievalService).retrieve(expectedSearchQuery);
    }

    @Test
    void dropsEvidenceWhenDocumentIsInvalidatedBeforeConsumption() {
        // KNOW-03：召回之后、加载元数据之前文档被停用/过期/撤销，证据必须整体失效并转人工。
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        KnowledgeDocumentRepository documentRepository = mock(KnowledgeDocumentRepository.class);
        KnowledgeCopilotSupportKnowledgeRetriever retriever =
                new KnowledgeCopilotSupportKnowledgeRetriever(retrievalService, documentRepository);
        SupportKnowledgeQuery query = new SupportKnowledgeQuery(
                "如何将 Acme 报告导出为 CSV？",
                TicketCategory.PRODUCT_USAGE,
                "用户询问报告导出步骤");
        KnowledgeChunk chunk = new KnowledgeChunk(
                17L, 9L, "导出", 0,
                "打开报告，选择导出，再选择 CSV。",
                "打开报告，选择导出", 12, Instant.now());
        KnowledgeDocument document = new KnowledgeDocument(
                9L, "Acme 报告手册", "upload", "report.txt",
                "release-smoke", "hash", false, Instant.now(), Instant.now());

        when(retrievalService.retrieve("用户询问报告导出步骤 如何将 Acme 报告导出为 CSV？ PRODUCT_USAGE",
                "PRODUCT")).thenReturn(List.of());
        when(retrievalService.retrieve("用户询问报告导出步骤 如何将 Acme 报告导出为 CSV？ PRODUCT_USAGE"))
                .thenReturn(List.of(new RetrievedKnowledgeChunk(chunk, 0.91d, "test-embedding")));
        when(documentRepository.findById(9L)).thenReturn(Optional.of(document));
        when(documentRepository.isRetrievalVisible(9L)).thenReturn(false);

        SupportKnowledgeResult result = retriever.retrieve(query);

        assertThat(result.hasResults()).isFalse();
        assertThat(result.reason()).contains("失效");
    }
}
