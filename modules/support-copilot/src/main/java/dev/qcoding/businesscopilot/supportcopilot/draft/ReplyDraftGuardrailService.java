package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guardrail service for reply draft validation.
 *
 * <p>回复草稿 guardrail 校验。检查引用完整性、禁止承诺内容和风险等级评估。</p>
 *
 * <p>校验规则：
 * <ul>
 *   <li>citation 的 chunkId 必须存在于本次 evidence 中</li>
 *   <li>禁止承诺退款、赔偿、开通、关闭、合同变更和明确处理时效</li>
 *   <li>禁止输出编造订单状态、账号状态等</li>
 *   <li>高风险类别升格风险等级</li>
 * </ul></p>
 */
public class ReplyDraftGuardrailService {

    private static final Logger log = LoggerFactory.getLogger(ReplyDraftGuardrailService.class);

    private static final String[] FORBIDDEN_COMMITMENTS = {
            "承诺退款", "保证退款", "可以退款", "马上退款", "立即退款",
            "承诺赔偿", "保证赔偿", "赔偿您", "补偿您", "一定赔偿",
            "立即开通", "马上开通", "立即关闭", "马上关闭", "合同变更",
            "24小时内处理", "24 小时内处理", "48小时内处理", "48 小时内处理",
            "一定给您", "保证给您", "马上处理", "立刻处理"
    };

    private final Set<String> highRiskCategories;

    public ReplyDraftGuardrailService(SupportCopilotProperties properties) {
        this.highRiskCategories = Arrays.stream(properties.highRiskCategories().split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    /**
     * Validate the LLM output against guardrail rules.
     *
     * @param output         the model output to validate
     * @param evidenceChunkIds comma-separated chunk IDs available as evidence (may be null)
     * @throws BusinessException if guardrail violations are found
     */
    public void validate(LlmReplyDraftOutput output, String evidenceChunkIds) {
        Set<String> allowedChunkIds = parseEvidenceChunkIds(evidenceChunkIds);
        String replyText = output.replyText() != null ? output.replyText().trim() : "";

        // 1. 有回复正文时，必须有本次检索证据和 citation。
        if (!replyText.isBlank()) {
            if (allowedChunkIds.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "缺少知识依据，不能生成确定客服回复");
            }
            List<LlmReplyDraftOutput.LlmCitation> citations = output.citations();
            if (citations == null || citations.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "回复草稿缺少引用依据，请重新生成草稿");
            }
            for (var citation : citations) {
                String chunkId = citation.chunkId() != null ? citation.chunkId().trim() : "";
                if (chunkId.isBlank() || !allowedChunkIds.contains(chunkId)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "回复引用的 chunkId '" + citation.chunkId() + "' 不在本次检索依据中，请重新生成草稿");
                }
            }
        }

        // 2. 禁止承诺检查。允许出现“退款流程”等业务名词，但禁止确定承诺。
        for (String forbidden : FORBIDDEN_COMMITMENTS) {
            if (replyText.contains(forbidden)) {
                log.warn("回复草稿包含禁止承诺：'{}'", forbidden);
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "回复草稿包含禁止承诺的内容: '" + forbidden + "'。请重新生成草稿。");
            }
        }
    }

    /**
     * Determine the effective risk level, considering category-based escalation.
     */
    public dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel effectiveRiskLevel(
            LlmReplyDraftOutput output,
            dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory category) {
        dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel modelRisk;
        try {
            modelRisk = output.riskLevel() == null
                    ? dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel.MEDIUM
                    : dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel.valueOf(
                            output.riskLevel().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            modelRisk = dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel.MEDIUM;
        }

        // 高风险类别自动升格到 HIGH
        if (category != null && highRiskCategories.contains(category.name())) {
            if (modelRisk != dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel.HIGH) {
                log.info("高风险类别触发风险升级：原级别={}，category={}，新级别=HIGH", modelRisk, category);
                return dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel.HIGH;
            }
        }
        return modelRisk;
    }

    private Set<String> parseEvidenceChunkIds(String evidenceChunkIds) {
        if (evidenceChunkIds == null || evidenceChunkIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(evidenceChunkIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
