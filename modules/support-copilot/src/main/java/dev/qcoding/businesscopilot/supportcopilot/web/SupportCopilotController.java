package dev.qcoding.businesscopilot.supportcopilot.web;

import dev.qcoding.businesscopilot.commonweb.api.ApiResponse;
import dev.qcoding.businesscopilot.commonweb.api.PageResponse;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationRequest;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftConfirmationService;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftResponse;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeResult;
import dev.qcoding.businesscopilot.supportcopilot.ticket.TicketAnalysisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for the Support Copilot module.
 *
 * <p>Support Copilot REST 控制器。提供工单分析、草稿确认/取消和审计日志查询：
 * <ul>
 *   <li>POST /api/support-copilot/tickets/analyze — 分析工单并生成回复草稿</li>
 *   <li>POST /api/support-copilot/reply-drafts/{draftId}/confirm — 确认草稿</li>
 *   <li>POST /api/support-copilot/reply-drafts/{draftId}/cancel — 取消草稿</li>
 *   <li>GET  /api/support-copilot/audit-logs — 审计日志分页</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/support-copilot")
public class SupportCopilotController {

    private final TicketAnalysisService analysisService;
    private final ReplyDraftConfirmationService confirmationService;
    private final SupportAuditService auditService;

    public SupportCopilotController(TicketAnalysisService analysisService,
                                     ReplyDraftConfirmationService confirmationService,
                                     SupportAuditService auditService) {
        this.analysisService = analysisService;
        this.confirmationService = confirmationService;
        this.auditService = auditService;
    }

    // ═══════════════════════════════════════════════════════════════
    // 工单分析
    // ═══════════════════════════════════════════════════════════════

    /** POST /api/support-copilot/tickets/analyze — 分析工单 */
    @PostMapping("/tickets/analyze")
    public ResponseEntity<ApiResponse<TicketAnalyzeResponse>> analyzeTicket(
            @Valid @RequestBody TicketClassificationRequest request) {
        TicketAnalysisService.TicketAnalysisResult result = analysisService.analyze(request);

        TicketAnalyzeResponse response = new TicketAnalyzeResponse(
                result.requestId(),
                result.ticketId(),
                result.classification().category(),
                result.classification().sentiment(),
                result.classification().urgency(),
                result.classification().summary(),
                result.draft(),
                buildEvidenceResponse(result.knowledgeResult()),
                result.draft() != null && result.draft().needsHuman());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ═══════════════════════════════════════════════════════════════
    // 草稿确认/取消
    // ═══════════════════════════════════════════════════════════════

    /** POST /api/support-copilot/reply-drafts/{draftId}/confirm — 确认草稿 */
    @PostMapping("/reply-drafts/{draftId}/confirm")
    public ResponseEntity<ApiResponse<DraftConfirmResponse>> confirmDraft(
            @PathVariable("draftId") Long draftId,
            @Valid @RequestBody DraftConfirmRequest request) {
        var result = confirmationService.confirm(draftId, request.confirmationToken());

        return ResponseEntity.ok(ApiResponse.ok(
                new DraftConfirmResponse(result.draftId(), result.status())));
    }

    /** POST /api/support-copilot/reply-drafts/{draftId}/cancel — 取消草稿 */
    @PostMapping("/reply-drafts/{draftId}/cancel")
    public ResponseEntity<ApiResponse<DraftConfirmResponse>> cancelDraft(
            @PathVariable("draftId") Long draftId,
            @Valid @RequestBody DraftConfirmRequest request) {
        var result = confirmationService.cancel(draftId, request.confirmationToken());

        return ResponseEntity.ok(ApiResponse.ok(
                new DraftConfirmResponse(result.draftId(), result.status())));
    }

    // ═══════════════════════════════════════════════════════════════
    // 审计日志
    // ═══════════════════════════════════════════════════════════════

    /** GET /api/support-copilot/audit-logs — 审计日志分页 */
    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<PageResponse<SupportAuditLog>>> getAuditLogs(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        List<SupportAuditLog> logs = auditService.findRecent(page, size);
        long total = auditService.count();
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(logs, page, size, total)));
    }

    // ═══════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════

    private List<EvidenceItem> buildEvidenceResponse(SupportKnowledgeResult result) {
        if (result == null || result.evidence() == null) return List.of();
        return result.evidence().stream()
                .map(e -> new EvidenceItem(e.chunkId(), e.sourceTitle(),
                        e.sectionTitle(), e.snippet(), e.similarity()))
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════

    /** Full ticket analyze response. */
    public record TicketAnalyzeResponse(
            String requestId,
            Long ticketId,
            String category,
            String sentiment,
            String urgency,
            String summary,
            ReplyDraftResponse draft,
            List<EvidenceItem> evidence,
            boolean needsHuman) {
    }

    /** Knowledge evidence item for response. */
    public record EvidenceItem(
            String chunkId,
            String sourceTitle,
            String sectionTitle,
            String snippet,
            double similarity) {
    }

    /** Request to confirm or cancel a draft. */
    public record DraftConfirmRequest(
            @jakarta.validation.constraints.NotBlank(message = "confirmationToken 不能为空")
            String confirmationToken) {
    }

    /** Response for draft confirm/cancel. */
    public record DraftConfirmResponse(Long draftId, String status) {
    }
}
