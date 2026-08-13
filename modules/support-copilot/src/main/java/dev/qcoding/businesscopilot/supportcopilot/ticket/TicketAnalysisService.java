package dev.qcoding.businesscopilot.supportcopilot.ticket;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
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
import dev.qcoding.businesscopilot.supportcopilot.draft.SupportDraftStatus;
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
    private static final String CLASSIFICATION_POLICY_VERSION = "support-classification-v2.0";
    private static final String REPLY_POLICY_VERSION = "support-reply-guardrails-v2.0";

    private final TicketClassificationService classificationService;
    private final SupportKnowledgeRetriever knowledgeRetriever;
    private final ReplyDraftService draftService;
    private final SupportTicketRepository ticketRepository;
    private final SupportAuditService auditService;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final SupportCopilotProperties properties;
    private final CurrentActorProvider actorProvider;

    public TicketAnalysisService(TicketClassificationService classificationService,
                                  SupportKnowledgeRetriever knowledgeRetriever,
                                  ReplyDraftService draftService,
                                  SupportTicketRepository ticketRepository,
                                  SupportAuditService auditService,
                                  SensitiveTextMasker sensitiveTextMasker,
                                  SupportCopilotProperties properties,
                                  CurrentActorProvider actorProvider) {
        this.classificationService = classificationService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.draftService = draftService;
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.properties = properties;
        this.actorProvider = actorProvider;
    }

    /**
     * Analyze a support ticket end-to-end: classify, retrieve knowledge, generate draft.
     *
     * @param request classification request with customer message
     * @return full analysis result
     */
    public TicketAnalysisResult analyze(TicketClassificationRequest request) {
        return analyzeInternal(request, null);
    }

    /** Continues the explicit enterprise flow for a previously imported ticket. */
    public TicketAnalysisResult analyzeStored(long ticketId) {
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        SupportTicket ticket = ticketRepository.claimForAnalysis(
                        ticketId, actor.actorId(), actor.hasRole(BusinessRole.ADMIN))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return analyzeInternal(new TicketClassificationRequest(
                ticket.customerMessage(), ticket.channel()), ticket);
    }

    private TicketAnalysisResult analyzeInternal(
            TicketClassificationRequest request, SupportTicket claimedTicket) {
        String requestId = UUID.randomUUID().toString();
        long startMs = System.currentTimeMillis();
        String modelName = null;
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        try {
            // Step 1: Classification
            TicketClassificationService.ClassificationInvocation classificationInvocation =
                    classificationService.classifyWithMetadata(request);
            TicketClassificationResponse classification = classificationInvocation.response();
            var classificationAi = classificationInvocation.aiMetadata();
            var classificationPrompt = classificationInvocation.promptMetadata();
            modelName = classificationAi != null ? classificationAi.modelName() : null;
            String maskedMessage = classificationService.maskedMessage(request.customerMessage());

            SupportTicket ticket;
            if (claimedTicket == null) {
                ticket = ticketRepository.save(new SupportTicket(
                        null, null, maskedMessage,
                        request.channel() != null ? request.channel() : "sample",
                        classification.category(), classification.sentiment(),
                        classification.urgency(), SupportTicketStatus.CLASSIFIED,
                        actor.actorId(), null, null));
            } else {
                if (!ticketRepository.updateClassification(
                        claimedTicket.id(), classification.category(),
                        classification.sentiment(), classification.urgency())) {
                    throw new BusinessException(ErrorCode.STATE_CONFLICT);
                }
                ticket = new SupportTicket(
                        claimedTicket.id(), claimedTicket.externalId(), maskedMessage,
                        claimedTicket.channel(), classification.category(),
                        classification.sentiment(), classification.urgency(),
                        SupportTicketStatus.CLASSIFIED, claimedTicket.ownerActorId(),
                        claimedTicket.createdAt(), Instant.now());
            }

            auditService.record(new SupportAuditLog(
                    null, requestId, ticket.id(), "CLASSIFIED",
                    classification.category().name(), classification.urgency().name(),
                    null, null, modelName,
                    System.currentTimeMillis() - startMs, null,
                    ticket.ownerActorId(), actor.actorId(),
                    classificationAi != null ? classificationAi.providerName() : null,
                    classificationAi != null ? classificationAi.providerRequestId() : null,
                    classificationPrompt != null ? classificationPrompt.name() : null,
                    classificationPrompt != null ? classificationPrompt.version() : null,
                    classificationPrompt != null ? classificationPrompt.contentHash() : null,
                    CLASSIFICATION_POLICY_VERSION, null,
                    classificationAi != null ? classificationAi.inputTokens() : null,
                    classificationAi != null ? classificationAi.outputTokens() : null,
                    classificationAi != null ? classificationAi.finishReason() : null,
                    null, null));

            // Step 2: Knowledge retrieval
            SupportKnowledgeQuery knowledgeQuery = new SupportKnowledgeQuery(
                    maskedMessage, classification.category(), classification.summary());
            SupportKnowledgeResult knowledgeResult = knowledgeRetriever.retrieve(knowledgeQuery);

            // Build knowledge evidence text and chunk IDs
            String evidenceText = buildEvidenceText(knowledgeResult.evidence());
            String evidenceChunkIds = buildEvidenceChunkIds(knowledgeResult.evidence());
            String knowledgeVersionIds = buildKnowledgeVersionIds(knowledgeResult.evidence());

            long classifyEndMs = System.currentTimeMillis();

            // Step 3: Draft generation (or needsHuman if no evidence)
            ReplyDraftResponse draftResponse;
            ReplyDraftService.DraftInvocation draftInvocation = null;
            if (!knowledgeResult.hasResults()) {
                // 没有知识依据时不生成确定回复，避免模型凭常识承诺客服动作。
                draftResponse = new ReplyDraftResponse(
                        null, "", "HIGH",
                        List.of("无知识依据，需人工处理或补充知识库内容"),
                        List.of(), null, null, true);
                ticketRepository.transitionStatus(
                        ticket.id(), SupportTicketStatus.CLASSIFIED, SupportTicketStatus.NEEDS_HUMAN);
            } else {
                ReplyDraftRequest draftRequest = new ReplyDraftRequest(
                        ticket.id(), maskedMessage,
                        classification.category(), classification.sentiment(),
                        classification.urgency(), classification.summary(),
                        classification.needsHuman(), evidenceText, evidenceChunkIds, knowledgeVersionIds);
                draftInvocation = draftService.generateWithMetadata(draftRequest);
                draftResponse = draftInvocation.response();

                // Update ticket with draft info
                if (draftResponse.draftId() != null) {
                    if (draftResponse.needsHuman()) {
                        ticketRepository.transitionStatus(
                                ticket.id(), SupportTicketStatus.CLASSIFIED, SupportTicketStatus.NEEDS_HUMAN);
                    } else {
                        ticketRepository.transitionStatus(
                                ticket.id(), SupportTicketStatus.CLASSIFIED, SupportTicketStatus.DRAFTED);
                    }
                } else if (draftResponse.needsHuman()) {
                    ticketRepository.transitionStatus(
                            ticket.id(), SupportTicketStatus.CLASSIFIED, SupportTicketStatus.NEEDS_HUMAN);
                }
            }

            // Audit: draft or needsHuman
            String eventType = draftResponse.needsHuman() ? "NEEDS_HUMAN" : "DRAFTED";
            long totalMs = System.currentTimeMillis() - startMs;
            var draftAi = draftInvocation != null ? draftInvocation.aiMetadata() : null;
            var draftPrompt = draftInvocation != null ? draftInvocation.promptMetadata() : null;
            String draftModel = draftAi != null ? draftAi.modelName() : modelName;

            auditService.record(new SupportAuditLog(
                    null, requestId, ticket.id(), eventType,
                    classification.category().name(), classification.urgency().name(),
                    draftResponse.riskLevel(), evidenceChunkIds, draftModel,
                    totalMs, null, ticket.ownerActorId(), actor.actorId(),
                    draftAi != null ? draftAi.providerName() : null,
                    draftAi != null ? draftAi.providerRequestId() : null,
                    draftPrompt != null ? draftPrompt.name() : null,
                    draftPrompt != null ? draftPrompt.version() : null,
                    draftPrompt != null ? draftPrompt.contentHash() : null,
                    REPLY_POLICY_VERSION, null,
                    draftAi != null ? draftAi.inputTokens() : null,
                    draftAi != null ? draftAi.outputTokens() : null,
                    draftAi != null ? draftAi.finishReason() : null,
                    null, null));

            log.info("工单分析完成：ticketId={}，category={}，needsHuman={}，latencyMs={}",
                    ticket.id(), classification.category(), draftResponse.needsHuman(), totalMs);

            return new TicketAnalysisResult(
                    requestId, ticket.id(), classification, draftResponse, knowledgeResult);

        } catch (BusinessException ex) {
            log.error("工单分析失败：requestId={}", requestId, ex);
            if (claimedTicket != null) {
                ticketRepository.failAnalysis(claimedTicket.id());
            }
            auditService.record(new SupportAuditLog(
                    null, requestId, claimedTicket == null ? null : claimedTicket.id(), "FAILED",
                    null, null, null, null, modelName,
                    System.currentTimeMillis() - startMs, null,
                    actor.actorId(), null,
                    null, null, null, null, null, null,
                    ex.errorCode().code(), null, null, null, null, null));
            throw ex;
        } catch (Exception ex) {
            log.error("工单分析发生未预期异常：requestId={}", requestId, ex);
            if (claimedTicket != null) {
                ticketRepository.failAnalysis(claimedTicket.id());
            }
            auditService.record(new SupportAuditLog(
                    null, requestId, claimedTicket == null ? null : claimedTicket.id(), "FAILED",
                    null, null, null, null, modelName,
                    System.currentTimeMillis() - startMs, null,
                    actor.actorId(), null,
                    null, null, null, null, null, null,
                    ErrorCode.INTERNAL_ERROR.code(), null, null, null, null, null));
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    ErrorCode.AI_MODEL_ERROR.defaultMessage(), ex);
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

    private String buildKnowledgeVersionIds(List<SupportKnowledgeEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "";
        }
        return evidence.stream()
                .map(SupportKnowledgeEvidence::versionReference)
                .distinct()
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
