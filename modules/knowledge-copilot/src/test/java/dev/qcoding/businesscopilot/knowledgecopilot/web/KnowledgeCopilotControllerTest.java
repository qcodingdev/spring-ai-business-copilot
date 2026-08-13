package dev.qcoding.businesscopilot.knowledgecopilot.web;

import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerStatus;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeCitation;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeQuestionService;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.KnowledgeAuditService;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.KnowledgeQaAuditLog;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadRequest;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocument;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJob;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJobStatus;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeAnswerFeedback;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeAnswerFeedbackRequest;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackRating;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackReason;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackService;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityMetrics;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityQueueItem;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityReview;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityReviewDecision;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityReviewRequest;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeEvidenceAssessment;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeAnswerAssessment;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeRemediationAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeCopilotControllerTest {

    private DocumentUploadService documentUploadService;
    private KnowledgeDocumentRepository documentRepository;
    private KnowledgeQuestionService questionService;
    private KnowledgeAuditService auditService;
    private KnowledgeFeedbackService feedbackService;
    private KnowledgeCopilotController controller;

    @BeforeEach
    void setUp() {
        documentUploadService = mock(DocumentUploadService.class);
        documentRepository = mock(KnowledgeDocumentRepository.class);
        questionService = mock(KnowledgeQuestionService.class);
        auditService = mock(KnowledgeAuditService.class);
        feedbackService = mock(KnowledgeFeedbackService.class);
        controller = new KnowledgeCopilotController(
                documentUploadService, documentRepository,
                questionService, auditService, feedbackService);
    }

    // ═══════════════════════════════════════════════════════════════
    // 文档上传 API
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /documents uploads and returns document response")
    void uploadDocumentReturnsCreatedResponse() {
        DocumentUploadRequest request = new DocumentUploadRequest("test.md", "# Hello", "HR");
        DocumentUploadResponse uploadResponse = new DocumentUploadResponse(1L, "test", 3, true, true);
        when(documentUploadService.upload(request)).thenReturn(uploadResponse);

        var response = controller.uploadDocument(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().documentId()).isEqualTo(1L);
        assertThat(response.getBody().data().chunkCount()).isEqualTo(3);
    }

    // ═══════════════════════════════════════════════════════════════
    // 文档列表 API
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /documents returns document list")
    void listDocumentsReturnsAllDocuments() {
        KnowledgeDocument doc1 = new KnowledgeDocument(
                1L, "Doc A", "upload", "a.md", "HR", "hashA", true, Instant.now(), Instant.now());
        KnowledgeDocument doc2 = new KnowledgeDocument(
                2L, "Doc B", "upload", "b.md", "IT", "hashB", false, Instant.now(), Instant.now());
        when(documentRepository.findAll()).thenReturn(List.of(doc1, doc2));

        var response = controller.listDocuments();

        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).hasSize(2);
    }

    @Test
    @DisplayName("GET /documents returns empty list when no documents")
    void listDocumentsReturnsEmpty() {
        when(documentRepository.findAll()).thenReturn(List.of());

        var response = controller.listDocuments();

        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 文档启用/停用 API
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PATCH /documents/{id}/enabled enables a document")
    void enableDocumentReturnsOk() {
        when(documentUploadService.updateEnabled(1L, true)).thenReturn(true);

        var request = new KnowledgeCopilotController.DocumentEnabledRequest(true);
        var response = controller.updateDocumentEnabled(1L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().enabled()).isTrue();
    }

    @Test
    @DisplayName("PATCH /documents/{id}/enabled disables a document")
    void disableDocumentReturnsOk() {
        when(documentUploadService.updateEnabled(1L, false)).thenReturn(true);

        var request = new KnowledgeCopilotController.DocumentEnabledRequest(false);
        var response = controller.updateDocumentEnabled(1L, request);

        assertThat(response.getBody().data().enabled()).isFalse();
    }

    @Test
    @DisplayName("PATCH /documents/{id}/enabled returns 404 for non-existent document")
    void updateEnabledReturns404() {
        when(documentUploadService.updateEnabled(999L, true)).thenReturn(false);

        var request = new KnowledgeCopilotController.DocumentEnabledRequest(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.updateDocumentEnabled(999L, request))
                .isInstanceOf(dev.qcoding.businesscopilot.commonweb.api.BusinessException.class)
                .hasMessageContaining("资源不存在");
    }

    @Test
    @DisplayName("POST /documents/{id}/reindex rebuilds embeddings")
    void reindexDocumentReturnsIndexResult() {
        KnowledgeIndexJob result = new KnowledgeIndexJob(
                10L, 1L, KnowledgeIndexJobStatus.PENDING, 0,
                null, null, null, null, null, null, null, null, null);
        when(documentUploadService.reindex(1L)).thenReturn(result);

        var response = controller.reindexDocument(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().data()).isEqualTo(result);
        verify(documentUploadService).reindex(1L);
    }

    // ═══════════════════════════════════════════════════════════════
    // 问答 API 成功
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /questions returns ANSWERED response with citations")
    void askQuestionReturnsAnswered() {
        var questionRequest = new dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerRequest("什么是年假？");
        KnowledgeAnswerResponse answerResponse = new KnowledgeAnswerResponse(
                KnowledgeAnswerStatus.ANSWERED,
                "年假是员工每年享有的带薪假期。",
                List.of(new KnowledgeCitation(1L, "年假政策说明")),
                List.of(),
                "test-model");
        when(questionService.askWithAudit(questionRequest)).thenReturn(
                new KnowledgeQuestionService.QuestionInvocation(
                        answerResponse, "1,2", "test-embedding", 25L,
                        null, null, null));
        when(auditService.record(any())).thenReturn(1L);

        var response = controller.askQuestion(questionRequest);

        assertThat(response.getBody().success()).isTrue();
        KnowledgeAnswerResponse data = response.getBody().data();
        assertThat(data.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
        assertThat(data.answer()).contains("年假");
        assertThat(data.citations()).hasSize(1);
        assertThat(data.answerId()).isEqualTo(1L);
        verify(auditService).record(any());
    }

    // ═══════════════════════════════════════════════════════════════
    // 问答 API NO_EVIDENCE
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /questions returns NO_EVIDENCE when no relevant chunks found")
    void askQuestionReturnsNoEvidence() {
        var questionRequest = new dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerRequest("未知话题");
        KnowledgeAnswerResponse answerResponse = new KnowledgeAnswerResponse(
                KnowledgeAnswerStatus.NO_EVIDENCE,
                null,
                List.of(),
                List.of("No chunks met similarity threshold"),
                "test-model");
        when(questionService.askWithAudit(questionRequest)).thenReturn(
                new KnowledgeQuestionService.QuestionInvocation(
                        answerResponse, null, "test-embedding", 20L,
                        null, null, "NO_RETRIEVED_EVIDENCE"));
        when(auditService.record(any())).thenReturn(1L);

        var response = controller.askQuestion(questionRequest);

        assertThat(response.getBody().data().status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);
        assertThat(response.getBody().data().answer()).isNull();
        assertThat(response.getBody().data().answerId()).isEqualTo(1L);
        verify(auditService).record(any());
    }

    @Test
    @DisplayName("POST /answers/{answerId}/feedback records current actor feedback")
    void submitFeedbackReturnsSavedFeedback() {
        var request = new KnowledgeAnswerFeedbackRequest(
                KnowledgeFeedbackRating.NOT_HELPFUL,
                KnowledgeFeedbackReason.MISSING_EVIDENCE,
                "缺少报销上限");
        var saved = new KnowledgeAnswerFeedback(
                3L, 17L, "operator",
                KnowledgeFeedbackRating.NOT_HELPFUL,
                KnowledgeFeedbackReason.MISSING_EVIDENCE,
                "缺少报销上限", Instant.now(), Instant.now());
        when(feedbackService.submit(17L, request)).thenReturn(saved);

        var response = controller.submitFeedback(17L, request);

        assertThat(response.getBody().data()).isEqualTo(saved);
        assertThat(response.getBody().message()).contains("质量复核");
    }

    @Test
    @DisplayName("GET /quality-queue returns paginated unresolved answers")
    void getQualityQueueReturnsPagination() {
        Instant issueUpdatedAt = Instant.now();
        var item = new KnowledgeQualityQueueItem(
                17L, "req-17", "报销上限是多少？", "旧制度中的上限为 2000 元。",
                "11,12", "11", "ANSWERED", null,
                KnowledgeFeedbackRating.NOT_HELPFUL,
                KnowledgeFeedbackReason.MISSING_EVIDENCE,
                "缺少上限", Instant.now(), issueUpdatedAt, 1L, issueUpdatedAt);
        when(feedbackService.findQualityQueue(0, 20)).thenReturn(List.of(item));
        when(feedbackService.countQualityQueue()).thenReturn(1L);

        var response = controller.getQualityQueue(0, 20);

        assertThat(response.getBody().data().content()).containsExactly(item);
        assertThat(response.getBody().data().totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /quality-queue/{answerId}/review records disposition")
    void reviewQualityIssueReturnsDisposition() {
        Instant issueUpdatedAt = Instant.now();
        var request = new KnowledgeQualityReviewRequest(
                KnowledgeQualityReviewDecision.KNOWLEDGE_UPDATE_REQUIRED,
                KnowledgeEvidenceAssessment.OUTDATED,
                KnowledgeAnswerAssessment.PARTIALLY_ACCURATE,
                KnowledgeRemediationAction.UPDATE_KNOWLEDGE,
                "需要补充最新报销制度",
                1L,
                issueUpdatedAt);
        var review = new KnowledgeQualityReview(
                8L, 17L,
                KnowledgeQualityReviewDecision.KNOWLEDGE_UPDATE_REQUIRED,
                KnowledgeEvidenceAssessment.OUTDATED,
                KnowledgeAnswerAssessment.PARTIALLY_ACCURATE,
                KnowledgeRemediationAction.UPDATE_KNOWLEDGE,
                "需要补充最新报销制度",
                "reviewer",
                1L,
                issueUpdatedAt,
                Instant.now(),
                Instant.now());
        when(feedbackService.review(17L, request)).thenReturn(review);

        var response = controller.reviewQualityIssue(17L, request);

        assertThat(response.getBody().data()).isEqualTo(review);
        assertThat(response.getBody().message()).contains("人工处置");
    }

    @Test
    @DisplayName("GET /quality-metrics returns low-cardinality counts")
    void getQualityMetricsReturnsCounts() {
        var metrics = new KnowledgeQualityMetrics(8, 5, 3, 2, 1, 1, 2);
        when(feedbackService.qualityMetrics()).thenReturn(metrics);

        var response = controller.getQualityMetrics();

        assertThat(response.getBody().data()).isEqualTo(metrics);
    }

    // ═══════════════════════════════════════════════════════════════
    // 审计日志 API
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /audit-logs returns paginated audit logs")
    void getAuditLogsReturnsPagination() {
        KnowledgeQaAuditLog log = new KnowledgeQaAuditLog(
                1L, "req-001", "什么是年假？", "1,2", "1,2",
                "ANSWERED", null, "test-model", "text-embedding-3-small",
                1500L, Instant.now());
        when(auditService.findRecent(0, 20)).thenReturn(List.of(log));
        when(auditService.count()).thenReturn(1L);

        var response = controller.getAuditLogs(0, 20);

        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().content()).hasSize(1);
        assertThat(response.getBody().data().totalElements()).isEqualTo(1L);
        assertThat(response.getBody().data().page()).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /audit-logs returns empty page when no logs")
    void getAuditLogsReturnsEmpty() {
        when(auditService.findRecent(0, 20)).thenReturn(List.of());
        when(auditService.count()).thenReturn(0L);

        var response = controller.getAuditLogs(0, 20);

        assertThat(response.getBody().data().content()).isEmpty();
        assertThat(response.getBody().data().totalElements()).isEqualTo(0L);
    }
}
