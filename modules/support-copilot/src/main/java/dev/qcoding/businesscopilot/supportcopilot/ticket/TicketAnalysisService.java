package dev.qcoding.businesscopilot.supportcopilot.ticket;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationRequest;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationResponse;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationService;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftRequest;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftResponse;
import dev.qcoding.businesscopilot.supportcopilot.draft.ReplyDraftService;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeEvidence;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeQuery;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeResult;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrator service that ties together classification, knowledge retrieval,
 * and reply draft generation for a support ticket analysis flow.
 *
 * <p>工单分析编排服务。将分类、知识检索和回复草稿生成串起来，
 * 形成完整的工单分析闭环。每一步都写入审计日志。</p>
 */
public class TicketAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TicketAnalysisService.class);

    private final TicketClassificationService classificationService;
    private final SupportKnowledgeRetriever knowledgeRetriever;
    private final ReplyDraftService draftService;
    private final SupportTicketRepository ticketRepository;
    private final SupportAuditService auditService;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final SupportCopilotProperties properties;

    public TicketAnalysisService(TicketClassificationService classificationService,
                                  SupportKnowledgeRetriever knowledgeRetriever,
                                  ReplyDraftService draftService,
                                  SupportTicketRepository ticketRepository,
                                  SupportAuditService auditService,
                                  SensitiveTextMasker sensitiveTextMasker,
                                  SupportCopilotProperties properties) {
        this.classificationService = classificationService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.draftService = draftService;
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.properties = properties;
    }

    /**
     * Analyze a support ticket end-to-end: classify, retrieve knowledge, generate draft.
     *
     * @param request classification request with customer message
     * @return full analysis result
     */
    public TicketAnalysisResult analyze(TicketClassificationRequest request) {
        String requestId = UUID.randomUUID().toString();
        long startMs = System.currentTimeMillis();
        String modelName = "unknown";

        try {
            // Step 1: Classification
            TicketClassificationResponse classification = classificationService.classify(request);
            String maskedMessage = classificationService.maskedMessage(request.customerMessage());

            // Persist ticket
            SupportTicket ticket = ticketRepository.save(new SupportTicket(
                    null, null, maskedMessage,
                    request.channel() != null ? request.channel() : "sample",
                    classification.category(), classification.sentiment(),
                    classification.urgency(), "DRAFTED", null, null));

            auditService.record(new SupportAuditLog(
                    null, requestId, ticket.id(), "CLASSIFIED",
                    classification.category(), classification.urgency(),
                    null, null, modelName,
                    System.currentTimeMillis() - startMs, null, null));

            // Step 2: Knowledge retrieval
            SupportKnowledgeQuery knowledgeQuery = new SupportKnowledgeQuery(
                    maskedMessage, classification.category(), classification.summary());
            SupportKnowledgeResult knowledgeResult = knowledgeRetriever.retrieve(knowledgeQuery);

            // Build knowledge evidence text and chunk IDs
            String evidenceText = buildEvidenceText(knowledgeResult.evidence());
            String evidenceChunkIds = buildEvidenceChunkIds(knowledgeResult.evidence());

            long classifyEndMs = System.currentTimeMillis();

            // Step 3: Draft generation (or needsHuman if no evidence)
            ReplyDraftResponse draftResponse;
            if (!knowledgeResult.hasResults()) {
                // 没有知识依据时不生成确定回复，避免模型凭常识承诺客服动作。
                draftResponse = new ReplyDraftResponse(
                        null, "", "HIGH",
                        List.of("无知识依据，需人工处理或补充知识库内容"),
                        List.of(), null, null, true);
                ticketRepository.updateStatus(ticket.id(), "NEEDS_HUMAN");
            } else {
                ReplyDraftRequest draftRequest = new ReplyDraftRequest(
                        ticket.id(), maskedMessage,
                        classification.category(), classification.sentiment(),
                        classification.urgency(), classification.summary(),
                        classification.needsHuman(), evidenceText, evidenceChunkIds);
                draftResponse = draftService.generate(draftRequest);

                // Update ticket with draft info
                if (draftResponse.draftId() != null) {
                    String newStatus = draftResponse.needsHuman() ? "NEEDS_HUMAN" : "DRAFTED";
                    ticketRepository.updateStatus(ticket.id(), newStatus);
                } else if (draftResponse.needsHuman()) {
                    ticketRepository.updateStatus(ticket.id(), "NEEDS_HUMAN");
                }
            }

            // Audit: draft or needsHuman
            String eventType = draftResponse.needsHuman() ? "NEEDS_HUMAN" : "DRAFTED";
            long totalMs = System.currentTimeMillis() - startMs;

            auditService.record(new SupportAuditLog(
                    null, requestId, ticket.id(), eventType,
                    classification.category(), classification.urgency(),
                    draftResponse.riskLevel(), evidenceChunkIds, modelName,
                    totalMs, null, null));

            log.info("Ticket analysis complete: ticketId={}, category={}, needsHuman={}, latencyMs={}",
                    ticket.id(), classification.category(), draftResponse.needsHuman(), totalMs);

            return new TicketAnalysisResult(
                    requestId, ticket.id(), classification, draftResponse, knowledgeResult);

        } catch (BusinessException ex) {
            log.error("Ticket analysis failed: requestId={}", requestId, ex);
            auditService.record(new SupportAuditLog(
                    null, requestId, null, "FAILED",
                    null, null, null, null, modelName,
                    System.currentTimeMillis() - startMs, ex.getMessage(), null));
            throw ex;
        } catch (Exception ex) {
            log.error("Ticket analysis failed unexpectedly: requestId={}", requestId, ex);
            auditService.record(new SupportAuditLog(
                    null, requestId, null, "FAILED",
                    null, null, null, null, modelName,
                    System.currentTimeMillis() - startMs, ex.getMessage(), null));
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    "工单分析处理失败: " + ex.getMessage(), ex);
        }
    }

    private String buildEvidenceText(List<SupportKnowledgeEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            SupportKnowledgeEvidence e = evidence.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append("来源: ").append(e.sourceTitle());
            if (e.sectionTitle() != null) sb.append(" > ").append(e.sectionTitle());
            sb.append("\n");
            sb.append("片段: ").append(e.snippet()).append("\n");
            sb.append("ChunkID: ").append(e.chunkId()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildEvidenceChunkIds(List<SupportKnowledgeEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return "";
        return evidence.stream()
                .map(SupportKnowledgeEvidence::chunkId)
                .collect(Collectors.joining(","));
    }

    /**
     * Result of a full ticket analysis.
     */
    public record TicketAnalysisResult(
            String requestId,
            Long ticketId,
            TicketClassificationResponse classification,
            ReplyDraftResponse draft,
            SupportKnowledgeResult knowledgeResult) {
    }
}
