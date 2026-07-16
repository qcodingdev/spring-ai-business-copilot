package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Service for generating support reply drafts via LLM.
 *
 * <p>回复草稿生成服务。调用 AI 模型基于工单内容和知识证据生成回复草稿。
 * 生成后通过 guardrail 校验，然后持久化并返回确认 token。</p>
 */
public class ReplyDraftService {

    private static final Logger log = LoggerFactory.getLogger(ReplyDraftService.class);

    private static final String PROMPT_TEMPLATE = "support-copilot/reply-draft.st";

    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final ReplyDraftGuardrailService guardrailService;
    private final SupportReplyDraftRepository draftRepository;
    private final SupportCopilotProperties properties;
    private final CurrentActorProvider actorProvider;
    private final ConfirmationTokenService tokenService;

    public ReplyDraftService(AiChatService aiChatService,
                             PromptTemplateService promptTemplateService,
                             SensitiveTextMasker sensitiveTextMasker,
                             ReplyDraftGuardrailService guardrailService,
                             SupportReplyDraftRepository draftRepository,
                             SupportCopilotProperties properties,
                             CurrentActorProvider actorProvider,
                             ConfirmationTokenService tokenService) {
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.guardrailService = guardrailService;
        this.draftRepository = draftRepository;
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.tokenService = tokenService;
    }

    /**
     * Generate a reply draft for a classified ticket.
     *
     * @param request draft generation request with ticket analysis and knowledge evidence
     * @return draft response with text, risk info, citations, and confirmation token
     */
    public ReplyDraftResponse generate(ReplyDraftRequest request) {
        return generateWithMetadata(request).response();
    }

    public DraftInvocation generateWithMetadata(ReplyDraftRequest request) {
        long startMs = System.currentTimeMillis();
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.authenticated()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        // 如果没有知识依据且已经标记需要转人工，直接返回
        if (request.needsHuman() && (request.knowledgeEvidence() == null || request.knowledgeEvidence().isBlank())) {
            log.info("Ticket {} flagged as needsHuman with no evidence — skipping draft generation", request.ticketId());
            return new DraftInvocation(buildNoEvidenceResponse(request), null, null);
        }

        // 构建知识依据文本
        String knowledgeText = request.knowledgeEvidence() != null && !request.knowledgeEvidence().isBlank()
                ? request.knowledgeEvidence()
                : "无可用知识依据";

        // 构建 prompt
        RenderedPrompt prompt = promptTemplateService.renderWithMetadata(PROMPT_TEMPLATE, "v1", Map.of(
                "customerMessage", request.customerMessage() != null ? request.customerMessage() : "",
                "category", request.category() != null ? request.category() : "UNKNOWN",
                "sentiment", request.sentiment() != null ? request.sentiment() : "NEUTRAL",
                "urgency", request.urgency() != null ? request.urgency() : "MEDIUM",
                "summary", request.summary() != null ? request.summary() : "",
                "knowledgeEvidence", knowledgeText));

        log.debug("Generating reply draft for ticket {}", request.ticketId());

        // 调用模型
        AiInvocationResult<LlmReplyDraftOutput> invocation;
        try {
            invocation = aiChatService.generateJsonWithMetadata(
                    prompt.content(), LlmReplyDraftOutput.class);
        } catch (Exception ex) {
            log.error("Reply draft generation model call failed for ticket {}", request.ticketId(), ex);
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    ErrorCode.AI_MODEL_ERROR.defaultMessage(), ex);
        }
        LlmReplyDraftOutput output = invocation.content();

        if (output == null || output.replyText() == null) {
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR, "回复草稿生成模型返回了无效结果");
        }

        // Guardrail 校验
        guardrailService.validate(output, request.evidenceChunkIds());

        // 如果分类已标记 needsHuman，草稿即使生成了也需要标记
        boolean effectiveNeedsHuman = request.needsHuman() || output.needsHuman();

        // 评估风险等级
        String riskLevel = guardrailService.effectiveRiskLevel(output, request.category());

        // 脱敏回复草稿
        String maskedReply = sensitiveTextMasker.mask(output.replyText());

        // 持久化草稿
        ConfirmationTokenService.IssuedToken token = tokenService.issue();
        Instant expiresAt = Instant.now().plusSeconds(properties.draftTtlMinutes() * 60L);
        boolean reviewQueue = effectiveNeedsHuman;
        String status = reviewQueue ? "NEEDS_REVIEW" : "DRAFTED";

        SupportReplyDraft draft = new SupportReplyDraft(
                null, request.ticketId(), maskedReply,
                request.evidenceChunkIds(),
                riskLevel,
                String.join("; ", output.riskReasons() != null ? output.riskReasons() : List.of()),
                token.rawToken(), token.digest(), status,
                actor.actorId(), reviewQueue, null,
                null, expiresAt, null, null);

        SupportReplyDraft saved = draftRepository.save(draft);

        // 构建 citations
        List<ReplyDraftResponse.Citation> citations = new ArrayList<>();
        if (output.citations() != null) {
            for (var c : output.citations()) {
                citations.add(new ReplyDraftResponse.Citation(
                        c.chunkId(), null, null, c.reason()));
            }
        }

        long latencyMs = System.currentTimeMillis() - startMs;
        log.info("Reply draft generated for ticket {}: draftId={}, riskLevel={}, needsHuman={}, latencyMs={}",
                request.ticketId(), saved.id(), riskLevel, effectiveNeedsHuman, latencyMs);

        ReplyDraftResponse response = new ReplyDraftResponse(
                saved.id(), maskedReply, riskLevel,
                output.riskReasons() != null ? output.riskReasons() : List.of(),
                citations, token.rawToken(), expiresAt.toString(),
                effectiveNeedsHuman);
        return new DraftInvocation(response, prompt.metadata(), invocation.metadata());
    }

    private ReplyDraftResponse buildNoEvidenceResponse(ReplyDraftRequest request) {
        return new ReplyDraftResponse(
                null, "", "HIGH",
                List.of("无足够知识依据，且工单已标记需要转人工"),
                List.of(), null, null, true);
    }

    public record DraftInvocation(
            ReplyDraftResponse response,
            PromptTemplateMetadata promptMetadata,
            AiInvocationMetadata aiMetadata) {
    }
}
