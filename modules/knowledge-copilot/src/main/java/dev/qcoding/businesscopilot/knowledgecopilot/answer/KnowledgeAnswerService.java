package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.knowledgecopilot.citation.CitationGuardrailService;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates structured answers from retrieved knowledge chunks via LLM.
 *
 * <p>知识答案生成服务。接收检索到的 chunks，调用 LLM 生成结构化的 JSON 答案，
 * 并通过 {@link CitationGuardrailService} 校验 citation 完整性。
 * 敏感内容通过 {@link SensitiveTextMasker} 进行二次脱敏。</p>
 *
 * <p>核心流程：
 * <ol>
 *   <li>将检索到的 chunks 序列化为 prompt 上下文</li>
 *   <li>调用 LLM 生成 JSON 结构化答案</li>
 *   <li>解析 JSON 输出为 {@link LlmAnswerOutput}</li>
 *   <li>校验 citation 完整性（chunkId 必须来自本次检索）</li>
 *   <li>脱敏检查（answer 中不得含敏感内容）</li>
 *   <li>组装最终 {@link KnowledgeAnswerResponse}</li>
 * </ol>
 * 如果检索结果为空或所有 chunk 相似度过低，直接返回 NO_EVIDENCE，不调用 LLM。</p>
 */
public class KnowledgeAnswerService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAnswerService.class);

    private static final String PROMPT_LOCATION = "knowledge-copilot/answer-generation.st";

    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final CitationGuardrailService citationGuardrailService;
    private final SensitiveTextMasker sensitiveTextMasker;

    public KnowledgeAnswerService(AiChatService aiChatService,
                                   PromptTemplateService promptTemplateService,
                                   CitationGuardrailService citationGuardrailService,
                                   SensitiveTextMasker sensitiveTextMasker) {
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.citationGuardrailService = citationGuardrailService;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    /**
     * Generate an answer based on the retrieved chunks.
     *
     * <p>如果 retrievedChunks 为空，直接返回 NO_EVIDENCE，不调用 LLM。</p>
     *
     * @param question        the user's question
     * @param retrievedChunks the chunks retrieved by {@code KnowledgeRetrievalService}
     * @return a structured answer response
     */
    public KnowledgeAnswerResponse answer(String question, List<RetrievedKnowledgeChunk> retrievedChunks) {
        return answerWithMetadata(question, retrievedChunks).response();
    }

    public AnswerInvocation answerWithMetadata(
            String question, List<RetrievedKnowledgeChunk> retrievedChunks) {
        // 1. 召回为空 → 直接拒答
        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            log.info("No chunks retrieved — returning NO_EVIDENCE for question: {}", truncate(question));
            return result(new KnowledgeAnswerResponse(
                    KnowledgeAnswerStatus.NO_EVIDENCE, null, List.of(), List.of(),
                    aiChatService.modelName()), null, null, "NO_RETRIEVED_EVIDENCE");
        }

        // 2. 序列化 chunks 为 prompt 上下文
        String contextChunks = formatContextChunks(retrievedChunks);

        // 3. 渲染 prompt 模板
        RenderedPrompt prompt = promptTemplateService.renderWithMetadata(PROMPT_LOCATION, "v1", Map.of(
                "contextChunks", contextChunks,
                "question", question));

        // 4. 调用 LLM 生成答案
        String modelName = aiChatService.modelName();
        log.debug("Invoking LLM model={} for question: {}", modelName, truncate(question));

        AiInvocationMetadata aiMetadata;
        LlmAnswerOutput llmOutput;
        try {
            AiInvocationResult<LlmAnswerOutput> invocation =
                    aiChatService.generateJsonWithMetadata(prompt.content(), LlmAnswerOutput.class);
            llmOutput = invocation.content();
            aiMetadata = invocation.metadata();
            if (aiMetadata != null && aiMetadata.modelName() != null) {
                modelName = aiMetadata.modelName();
            }
        } catch (BusinessException ex) {
            // JSON 解析失败 → 返回 REJECTED
            log.error("Failed to parse LLM output as JSON", ex);
            return result(new KnowledgeAnswerResponse(
                    KnowledgeAnswerStatus.REJECTED, null, List.of(),
                    List.of("AI model output could not be parsed"),
                    modelName), prompt.metadata(), null, ex.errorCode().code());
        }

        if (llmOutput == null) {
            return result(new KnowledgeAnswerResponse(
                    KnowledgeAnswerStatus.REJECTED, null, List.of(),
                    List.of("AI model returned empty output"),
                    modelName), prompt.metadata(), aiMetadata, "EMPTY_MODEL_OUTPUT");
        }

        // 5. 处理 NO_EVIDENCE 状态
        if (!"ANSWERED".equals(llmOutput.status())) {
            log.info("LLM returned status={} for question: {}", llmOutput.status(), truncate(question));
            return result(new KnowledgeAnswerResponse(
                    KnowledgeAnswerStatus.NO_EVIDENCE, null, List.of(),
                    llmOutput.warnings() != null ? llmOutput.warnings() : List.of(),
                    modelName), prompt.metadata(), aiMetadata, "MODEL_NO_EVIDENCE");
        }

        // 6. 转换 citations
        List<KnowledgeCitation> citations = convertCitations(llmOutput);

        // 7. Citation 完整性校验
        CitationGuardrailService.CitationValidationResult validation =
                citationGuardrailService.validate(citations, retrievedChunks);

        if (!validation.valid()) {
            log.warn("Citation validation failed — rejecting answer. Violations: {}",
                    validation.violations());
            List<String> warnings = new ArrayList<>();
            warnings.add("Citation guardrail violation: answer rejected");
            warnings.addAll(validation.violations());
            return result(new KnowledgeAnswerResponse(
                    KnowledgeAnswerStatus.REJECTED, null, List.of(), warnings, modelName),
                    prompt.metadata(), aiMetadata, "CITATION_VALIDATION_FAILED");
        }

        // 8. 敏感内容脱敏检查
        String answer = llmOutput.answer() != null ? llmOutput.answer() : "";
        List<String> warnings = llmOutput.warnings() != null ? new ArrayList<>(llmOutput.warnings()) : new ArrayList<>();

        if (sensitiveTextMasker.containsSensitive(answer)) {
            log.warn("Sensitive content detected in answer — masking");
            answer = sensitiveTextMasker.mask(answer);
            warnings.add("Sensitive content was detected and masked in the answer");
        }

        // 9. ANSWERED 状态必须至少有一个 citation（双重检查）
        if (citations.isEmpty()) {
            log.warn("ANSWERED status but no citations after validation — rejecting");
            return result(new KnowledgeAnswerResponse(
                    KnowledgeAnswerStatus.REJECTED, null, List.of(),
                    List.of("ANSWERED status requires at least one citation, but none survived validation"),
                    modelName), prompt.metadata(), aiMetadata, "CITATION_REQUIRED");
        }

        log.info("Answer generated successfully with {} citations for question: {}",
                citations.size(), truncate(question));
        return result(new KnowledgeAnswerResponse(
                KnowledgeAnswerStatus.ANSWERED, answer, citations, warnings, modelName),
                prompt.metadata(), aiMetadata, null);
    }

    private AnswerInvocation result(KnowledgeAnswerResponse response,
                                    PromptTemplateMetadata promptMetadata,
                                    AiInvocationMetadata aiMetadata,
                                    String violationCodes) {
        return new AnswerInvocation(response, promptMetadata, aiMetadata, violationCodes);
    }

    /**
     * Format retrieved chunks into a context block for the LLM prompt.
     */
    private String formatContextChunks(List<RetrievedKnowledgeChunk> retrievedChunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < retrievedChunks.size(); i++) {
            RetrievedKnowledgeChunk r = retrievedChunks.get(i);
            if (i > 0) {
                sb.append("\n---\n");
            }
            sb.append("chunkId=").append(r.chunk().id());
            if (r.chunk().sectionTitle() != null && !r.chunk().sectionTitle().isBlank()) {
                sb.append("  section=").append(r.chunk().sectionTitle());
            }
            sb.append("\n").append(r.chunk().content());
        }
        return sb.toString();
    }

    private List<KnowledgeCitation> convertCitations(LlmAnswerOutput llmOutput) {
        if (llmOutput.citations() == null || llmOutput.citations().isEmpty()) {
            return List.of();
        }
        return llmOutput.citations().stream()
                .map(c -> new KnowledgeCitation(c.chunkId(), c.excerpt()))
                .toList();
    }

    private static String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    public record AnswerInvocation(
            KnowledgeAnswerResponse response,
            PromptTemplateMetadata promptMetadata,
            AiInvocationMetadata aiMetadata,
            String violationCodes) {
    }
}
