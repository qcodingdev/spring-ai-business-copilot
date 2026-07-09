package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
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

    public ReplyDraftService(AiChatService aiChatService,
                             PromptTemplateService promptTemplateService,
                             SensitiveTextMasker sensitiveTextMasker,
                             ReplyDraftGuardrailService guardrailService,
                             SupportReplyDraftRepository draftRepository,
                             SupportCopilotProperties properties) {
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.guardrailService = guardrailService;
        this.draftRepository = draftRepository;
        this.properties = properties;
    }

    /**
     * Generate a reply draft for a classified ticket.
     *
     * @param request draft generation request with ticket analysis and knowledge evidence
     * @return draft response with text, risk info, citations, and confirmation token
     */
    public ReplyDraftResponse generate(ReplyDraftRequest request) {
        long startMs = System.currentTimeMillis();

        // 如果没有知识依据且已经标记需要转人工，直接返回
        if (request.needsHuman() && (request.knowledgeEvidence() == null || request.knowledgeEvidence().isBlank())) {
            log.info("Ticket {} flagged as needsHuman with no evidence — skipping draft generation", request.ticketId());
            return buildNoEvidenceResponse(request);
        }

        // 构建知识依据文本
        String knowledgeText = request.knowledgeEvidence() != null && !request.knowledgeEvidence().isBlank()
                ? request.knowledgeEvidence()
                : "无可用知识依据";

        // 构建 prompt
        String prompt = promptTemplateService.render(PROMPT_TEMPLATE, Map.of(
                "customerMessage", request.customerMessage() != null ? request.customerMessage() : "",
                "category", request.category() != null ? request.category() : "UNKNOWN",
                "sentiment", request.sentiment() != null ? request.sentiment() : "NEUTRAL",
                "urgency", request.urgency() != null ? request.urgency() : "MEDIUM",
                "summary", request.summary() != null ? request.summary() : "",
                "knowledgeEvidence", knowledgeText));

        log.debug("Generating reply draft for ticket {}", request.ticketId());

        // 调用模型
        LlmReplyDraftOutput output;
        try {
            output = aiChatService.generateJson(prompt, LlmReplyDraftOutput.class);
        } catch (Exception ex) {
            log.error("Reply draft generation model call failed for ticket {}", request.ticketId(), ex);
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    "回复草稿生成模型调用失败: " + ex.getMessage(), ex);
        }

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
        String confirmationToken = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(properties.draftTtlMinutes() * 60L);

        SupportReplyDraft draft = new SupportReplyDraft(
                null, request.ticketId(), maskedReply,
                request.evidenceChunkIds(),
                riskLevel,
                String.join("; ", output.riskReasons() != null ? output.riskReasons() : List.of()),
                confirmationToken, expiresAt, null);

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

        return new ReplyDraftResponse(
                saved.id(), maskedReply, riskLevel,
                output.riskReasons() != null ? output.riskReasons() : List.of(),
                citations, confirmationToken, expiresAt.toString(),
                effectiveNeedsHuman);
    }

    private ReplyDraftResponse buildNoEvidenceResponse(ReplyDraftRequest request) {
        return new ReplyDraftResponse(
                null, "", "HIGH",
                List.of("无足够知识依据，且工单已标记需要转人工"),
                List.of(), null, null, true);
    }
}
