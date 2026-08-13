package dev.qcoding.businesscopilot.knowledgecopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.commonweb.api.PageResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerRequest;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeQuestionService;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.KnowledgeAuditService;
import dev.qcoding.businesscopilot.knowledgecopilot.audit.KnowledgeQaAuditLog;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadRequest;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocument;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJob;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeAnswerFeedback;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeAnswerFeedbackRequest;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackService;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityMetrics;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityQueueItem;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityReview;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityReviewRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * REST API for the Knowledge Copilot module.
 *
 * <p>Knowledge Copilot REST 控制器。提供文档管理、知识问答和审计日志三大类端点：
 * <ul>
 *   <li>POST /api/knowledge-copilot/documents — 上传文档</li>
 *   <li>GET  /api/knowledge-copilot/documents — 文档列表</li>
 *   <li>PATCH /api/knowledge-copilot/documents/{documentId}/enabled — 启用/停用文档</li>
 *   <li>POST /api/knowledge-copilot/questions — 知识问答</li>
 *   <li>GET  /api/knowledge-copilot/audit-logs — 审计日志</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/knowledge-copilot")
@ConditionalOnProperty(prefix = "business-copilot.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeCopilotController {

    private final DocumentUploadService documentUploadService;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeQuestionService questionService;
    private final KnowledgeAuditService auditService;
    private final KnowledgeFeedbackService feedbackService;

    public KnowledgeCopilotController(DocumentUploadService documentUploadService,
                                       KnowledgeDocumentRepository documentRepository,
                                       KnowledgeQuestionService questionService,
                                       KnowledgeAuditService auditService,
                                       KnowledgeFeedbackService feedbackService) {
        this.documentUploadService = documentUploadService;
        this.documentRepository = documentRepository;
        this.questionService = questionService;
        this.auditService = auditService;
        this.feedbackService = feedbackService;
    }

    // ═══════════════════════════════════════════════════════════════
    // 文档管理
    // ═══════════════════════════════════════════════════════════════

    /** POST /api/knowledge-copilot/documents — 上传文档 */
    @PostMapping("/documents")
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> uploadDocument(
            @Valid @RequestBody DocumentUploadRequest request) {
        DocumentUploadResponse response = documentUploadService.upload(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, "文档上传成功"));
    }

    /** POST /api/knowledge-copilot/documents/file — 上传 TXT/Markdown/PDF/DOCX 文件。 */
    @PostMapping(path = "/documents/file", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> uploadDocumentFile(
            @RequestPart("file") MultipartFile file,
            @RequestPart(name = "category", required = false) String category,
            @RequestPart(name = "logicalDocumentId", required = false) UUID logicalDocumentId)
            throws java.io.IOException {
        DocumentUploadResponse response = documentUploadService.uploadFile(
                file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                category, logicalDocumentId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                ApiResponse.ok(response, "文档已接收，正在异步建立索引"));
    }

    /** GET /api/knowledge-copilot/documents — 文档列表 */
    @GetMapping("/documents")
    public ResponseEntity<ApiResponse<List<KnowledgeDocument>>> listDocuments() {
        List<KnowledgeDocument> documents = documentRepository.findAll();
        return ResponseEntity.ok(ApiResponse.ok(documents));
    }

    /** PATCH /api/knowledge-copilot/documents/{documentId}/enabled — 启用/停用文档 */
    @PatchMapping("/documents/{documentId}/enabled")
    public ResponseEntity<ApiResponse<DocumentEnabledResponse>> updateDocumentEnabled(
            @PathVariable("documentId") Long documentId,
            @Valid @RequestBody DocumentEnabledRequest request) {
        boolean updated = documentUploadService.updateEnabled(documentId, request.enabled());
        if (!updated) {
            throw new dev.qcoding.businesscopilot.commonweb.api.BusinessException(
                    dev.qcoding.businesscopilot.commonweb.api.ErrorCode.NOT_FOUND);
        }
        return ResponseEntity.ok(ApiResponse.ok(
                new DocumentEnabledResponse(documentId, request.enabled()),
                request.enabled() ? "文档已启用" : "文档已停用"));
    }

    /** POST /api/knowledge-copilot/documents/{documentId}/reindex — 重建文档向量索引 */
    @PostMapping("/documents/{documentId}/reindex")
    public ResponseEntity<ApiResponse<KnowledgeIndexJob>> reindexDocument(
            @PathVariable("documentId") Long documentId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(documentUploadService.reindex(documentId), "已创建索引重建任务"));
    }

    @GetMapping("/index-jobs/{jobId}")
    public ResponseEntity<ApiResponse<KnowledgeIndexJob>> getIndexJob(@PathVariable Long jobId) {
        return ResponseEntity.ok(ApiResponse.ok(documentUploadService.indexJob(jobId)));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable Long documentId) {
        if (!documentUploadService.delete(documentId)) {
            throw new dev.qcoding.businesscopilot.commonweb.api.BusinessException(
                    dev.qcoding.businesscopilot.commonweb.api.ErrorCode.NOT_FOUND);
        }
        return ResponseEntity.ok(ApiResponse.ok(null, "文档版本已删除"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 知识问答
    // ═══════════════════════════════════════════════════════════════

    /** POST /api/knowledge-copilot/questions — 知识问答 */
    @PostMapping("/questions")
    public ResponseEntity<ApiResponse<KnowledgeAnswerResponse>> askQuestion(
            @Valid @RequestBody KnowledgeAnswerRequest request) {
        KnowledgeQuestionService.QuestionInvocation invocation =
                questionService.askWithAudit(request);
        KnowledgeAnswerResponse response = invocation.response();

        // 审计记录（不中断主流程）
        Long answerId = auditService.record(buildAuditLog(invocation.sanitizedQuestion(), invocation));
        response = response.withAnswerId(answerId);

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** POST /api/knowledge-copilot/answers/{answerId}/feedback — 提交或更新本人反馈。 */
    @PostMapping("/answers/{answerId}/feedback")
    public ResponseEntity<ApiResponse<KnowledgeAnswerFeedback>> submitFeedback(
            @PathVariable Long answerId,
            @Valid @RequestBody KnowledgeAnswerFeedbackRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                feedbackService.submit(answerId, request),
                "反馈已记录，将用于知识质量复核"));
    }

    /** GET /api/knowledge-copilot/quality-queue — 无依据、拒绝和负反馈复核队列。 */
    @GetMapping("/quality-queue")
    public ResponseEntity<ApiResponse<PageResponse<KnowledgeQualityQueueItem>>> getQualityQueue(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        List<KnowledgeQualityQueueItem> items = feedbackService.findQualityQueue(page, size);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(
                items, page, size, feedbackService.countQualityQueue())));
    }

    /** POST /api/knowledge-copilot/quality-queue/{answerId}/review — 人工处置质量问题。 */
    @PostMapping("/quality-queue/{answerId}/review")
    public ResponseEntity<ApiResponse<KnowledgeQualityReview>> reviewQualityIssue(
            @PathVariable Long answerId,
            @Valid @RequestBody KnowledgeQualityReviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                feedbackService.review(answerId, request),
                "质量问题已记录人工处置结果"));
    }

    /** GET /api/knowledge-copilot/quality-metrics — 低基数质量闭环统计。 */
    @GetMapping("/quality-metrics")
    public ResponseEntity<ApiResponse<KnowledgeQualityMetrics>> getQualityMetrics() {
        return ResponseEntity.ok(ApiResponse.ok(feedbackService.qualityMetrics()));
    }

    // ═══════════════════════════════════════════════════════════════
    // 审计日志
    // ═══════════════════════════════════════════════════════════════

    /** GET /api/knowledge-copilot/audit-logs — 审计日志分页 */
    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<PageResponse<KnowledgeQaAuditLog>>> getAuditLogs(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        List<KnowledgeQaAuditLog> logs = auditService.findRecent(page, size);
        long total = auditService.count();
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(logs, page, size, total)));
    }

    // ═══════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════

    private KnowledgeQaAuditLog buildAuditLog(
            String question, KnowledgeQuestionService.QuestionInvocation invocation) {
        KnowledgeAnswerResponse response = invocation.response();
        String citedIds = response.citations() != null && !response.citations().isEmpty()
                ? response.citations().stream()
                    .map(c -> String.valueOf(c.chunkId()))
                    .reduce((a, b) -> a + "," + b).orElse("")
                : null;

        String refusalReason = response.status()
                != dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerStatus.ANSWERED
                ? response.status().name() : null;
        var aiMetadata = invocation.aiMetadata();
        var promptMetadata = invocation.promptMetadata();

        return new KnowledgeQaAuditLog(
                null,
                UUID.randomUUID().toString(),
                question,
                boundedAnswerPreview(response.answer()),
                invocation.retrievedChunkIds(),
                citedIds,
                response.status().name(),
                refusalReason,
                response.modelName(),
                invocation.embeddingModel(),
                invocation.latencyMs(),
                null, null,
                aiMetadata != null ? aiMetadata.providerName() : null,
                aiMetadata != null ? aiMetadata.providerRequestId() : null,
                promptMetadata != null ? promptMetadata.name() : null,
                promptMetadata != null ? promptMetadata.version() : null,
                promptMetadata != null ? promptMetadata.contentHash() : null,
                "knowledge-citation-guardrails-v2.0",
                invocation.violationCodes(),
                aiMetadata != null ? aiMetadata.inputTokens() : null,
                aiMetadata != null ? aiMetadata.outputTokens() : null,
                aiMetadata != null ? aiMetadata.finishReason() : null,
                null,
                null);
    }

    private String boundedAnswerPreview(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String normalized = answer.trim();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    // ═══════════════════════════════════════════════════════════════
    // inline DTOs
    // ═══════════════════════════════════════════════════════════════

    /** Request to toggle document enabled status. */
    public record DocumentEnabledRequest(boolean enabled) {
    }

    /** Response for document enabled toggle. */
    public record DocumentEnabledResponse(Long documentId, boolean enabled) {
    }
}
