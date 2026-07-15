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
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public KnowledgeCopilotController(DocumentUploadService documentUploadService,
                                       KnowledgeDocumentRepository documentRepository,
                                       KnowledgeQuestionService questionService,
                                       KnowledgeAuditService auditService) {
        this.documentUploadService = documentUploadService;
        this.documentRepository = documentRepository;
        this.questionService = questionService;
        this.auditService = auditService;
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
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.ok(
                new DocumentEnabledResponse(documentId, request.enabled()),
                request.enabled() ? "文档已启用" : "文档已停用"));
    }

    /** POST /api/knowledge-copilot/documents/{documentId}/reindex — 重建文档向量索引 */
    @PostMapping("/documents/{documentId}/reindex")
    public ResponseEntity<ApiResponse<EmbeddingIndexResult>> reindexDocument(
            @PathVariable("documentId") Long documentId) {
        return ResponseEntity.ok(ApiResponse.ok(documentUploadService.reindex(documentId), "文档索引已重建"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 知识问答
    // ═══════════════════════════════════════════════════════════════

    /** POST /api/knowledge-copilot/questions — 知识问答 */
    @PostMapping("/questions")
    public ResponseEntity<ApiResponse<KnowledgeAnswerResponse>> askQuestion(
            @Valid @RequestBody KnowledgeAnswerRequest request) {
        KnowledgeAnswerResponse response = questionService.ask(request);

        // 审计记录（不中断主流程）
        auditService.record(buildAuditLog(request.question(), response));

        return ResponseEntity.ok(ApiResponse.ok(response));
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

    private KnowledgeQaAuditLog buildAuditLog(String question, KnowledgeAnswerResponse response) {
        String retrievedIds = response.citations() != null && !response.citations().isEmpty()
                ? response.citations().stream()
                    .map(c -> String.valueOf(c.chunkId()))
                    .reduce((a, b) -> a + "," + b).orElse("")
                : null;

        String citedIds = response.citations() != null && !response.citations().isEmpty()
                ? response.citations().stream()
                    .map(c -> String.valueOf(c.chunkId()))
                    .reduce((a, b) -> a + "," + b).orElse("")
                : null;

        String refusalReason = null;
        if (response.status() != dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerStatus.ANSWERED) {
            refusalReason = response.warnings() != null && !response.warnings().isEmpty()
                    ? String.join("; ", response.warnings())
                    : response.status().name();
        }

        return new KnowledgeQaAuditLog(
                null,
                UUID.randomUUID().toString(),
                question,
                retrievedIds,
                citedIds,
                response.status().name(),
                refusalReason,
                response.modelName(),
                null,  // embeddingModel recorded during retrieval, not exposed in response
                0L,    // latencyMs filled by caller if needed
                null);
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
