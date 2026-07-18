package dev.qcoding.businesscopilot.supportcopilot.classification;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for classifying support tickets via LLM.
 *
 * <p>工单分类服务。调用 AI 模型对客户消息进行分类、情绪识别和紧急程度判断。
 * 输入入库前必须脱敏，模型输出为结构化 JSON。</p>
 *
 * <p>退款、账号安全、生产故障等高风险类别默认标记 needsHuman=true。
 * 模型调用失败时返回清晰错误，不静默降级。</p>
 */
public class TicketClassificationService {

    private static final Logger log = LoggerFactory.getLogger(TicketClassificationService.class);

    private static final String PROMPT_TEMPLATE = "support-copilot/ticket-classification.st";

    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final SensitiveTextMasker sensitiveTextMasker;
    private final SupportCopilotProperties properties;

    private final Set<TicketCategory> highRiskCategories;

    public TicketClassificationService(AiChatService aiChatService,
                                        PromptTemplateService promptTemplateService,
                                        SensitiveTextMasker sensitiveTextMasker,
                                        SupportCopilotProperties properties) {
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.properties = properties;
        this.highRiskCategories = Arrays.stream(properties.highRiskCategories().split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .map(TicketCategory::valueOf)
                .collect(Collectors.toSet());
    }

    /**
     * Classify a customer ticket message.
     *
     * <p>流程：
     * <ol>
     *   <li>校验 customerMessage 非空且不超过 max-ticket-length</li>
     *   <li>脱敏客户输入（用于 prompt 和入库）</li>
     *   <li>构建 prompt，调用模型生成结构化分类结果</li>
     *   <li>高风险类别自动标记 needsHuman</li>
     * </ol></p>
     *
     * @param request classification request with raw customer message
     * @return classification result
     * @throws BusinessException on validation failure or model error
     */
    public TicketClassificationResponse classify(TicketClassificationRequest request) {
        return classifyWithMetadata(request).response();
    }

    public ClassificationInvocation classifyWithMetadata(TicketClassificationRequest request) {
        String rawMessage = request.customerMessage();
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "客户消息不能为空。");
        }
        if (rawMessage.length() > properties.maxTicketLength()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "customerMessage 长度超过限制：" + rawMessage.length() + " > " + properties.maxTicketLength());
        }

        // 脱敏后用于 prompt 调用
        String maskedMessage = sensitiveTextMasker.mask(rawMessage);

        // 构建 prompt
        RenderedPrompt prompt = promptTemplateService.renderWithMetadata(PROMPT_TEMPLATE, "v2.0",
                Map.of("customerMessage", maskedMessage));

        log.debug("开始工单分类：消息长度={}，channel={}",
                rawMessage.length(), request.channel());

        // 调用模型
        AiInvocationResult<LlmClassificationOutput> invocation;
        try {
            invocation = aiChatService.generateJsonWithMetadata(
                    prompt.content(), LlmClassificationOutput.class);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("工单分类模型调用失败", ex);
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    ErrorCode.AI_MODEL_ERROR.defaultMessage(), ex);
        }
        LlmClassificationOutput output = invocation.content();

        // 验证模型输出
        if (output == null || output.category() == null) {
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    "工单分类模型返回了无效结果");
        }

        // 高风险类别强制 needsHuman
        TicketCategory category = parseEnum(TicketCategory.class, output.category(), TicketCategory.OTHER);
        TicketSentiment sentiment = parseEnum(
                TicketSentiment.class, output.sentiment(), TicketSentiment.NEUTRAL);
        TicketUrgency urgency = parseEnum(
                TicketUrgency.class, output.urgency(), TicketUrgency.MEDIUM);
        boolean effectiveNeedsHuman = output.needsHuman() || highRiskCategories.contains(category);

        List<String> effectiveReasons = output.reasons() != null
                ? new java.util.ArrayList<>(output.reasons())
                : new java.util.ArrayList<>();

        if (effectiveNeedsHuman && !Boolean.TRUE.equals(output.needsHuman())) {
            effectiveReasons.add("高风险类别自动触发转人工: " + category);
        }

        TicketClassificationResponse response = new TicketClassificationResponse(
                category,
                sentiment,
                urgency,
                output.summary(),
                effectiveNeedsHuman,
                effectiveReasons);

        log.info("工单分类完成：category={}，sentiment={}，urgency={}，needsHuman={}",
                response.category(), response.sentiment(), response.urgency(), response.needsHuman());

        return new ClassificationInvocation(
                response, prompt.metadata(), invocation.metadata());
    }

    /** Return the masked version of a customer message for storage. */
    public String maskedMessage(String rawMessage) {
        return sensitiveTextMasker.mask(rawMessage);
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("模型返回不支持的 {} 值，已使用兜底值", type.getSimpleName());
            return fallback;
        }
    }

    public record ClassificationInvocation(
            TicketClassificationResponse response,
            PromptTemplateMetadata promptMetadata,
            AiInvocationMetadata aiMetadata) {
    }
}
