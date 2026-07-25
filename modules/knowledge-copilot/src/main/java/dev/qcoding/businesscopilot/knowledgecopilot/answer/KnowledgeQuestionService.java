package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeRetrievalService;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Main orchestration service for the Knowledge Copilot question-answering pipeline.
 *
 * <p>知识问答主流程编排服务。接收用户问题，协调检索和答案生成：
 * <ol>
 *   <li>调用 {@link KnowledgeRetrievalService} 检索相关 chunks</li>
 *   <li>调用 {@link KnowledgeAnswerService} 生成结构化答案</li>
 * </ol>
 * 不处理多轮对话记忆、流式输出或跨用户权限过滤。</p>
 */
@Validated
public class KnowledgeQuestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionService.class);

    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeAnswerService answerService;
    private final SensitiveTextMasker sensitiveTextMasker;

    public KnowledgeQuestionService(KnowledgeRetrievalService retrievalService,
                                     KnowledgeAnswerService answerService,
                                     SensitiveTextMasker sensitiveTextMasker) {
        this.retrievalService = retrievalService;
        this.answerService = answerService;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    /**
     * Answer a question using the knowledge base.
     *
     * <p>完整问答流程：
     * <ol>
     *   <li>检索：问题向量化 → 从 enabled 文档中检索 topK 相似 chunks</li>
     *   <li>答案生成：基于检索到的 chunks 调用 LLM 生成结构化答案</li>
     *   <li>Guardrail：校验 citation 完整性，脱敏检查</li>
     * </ol>
     * 检索结果为空或所有 chunk 相似度过低时，直接返回 NO_EVIDENCE，不调用 LLM。</p>
     *
     * @param request the question request
     * @return structured answer response with status, answer, citations, and warnings
     */
    public KnowledgeAnswerResponse ask(@Valid KnowledgeAnswerRequest request) {
        return askWithAudit(request).response();
    }

    public QuestionInvocation askWithAudit(@Valid KnowledgeAnswerRequest request) {
        String question = sensitiveTextMasker.mask(request.question().trim());
        log.info("知识问答开始：脱敏后问题长度={}", question.length());

        long startTime = System.currentTimeMillis();

        // 1. 检索
        List<RetrievedKnowledgeChunk> retrievedChunks = retrievalService.retrieve(question, request.category());

        // 2. 答案生成（含 citation 校验和脱敏）
        KnowledgeAnswerService.AnswerInvocation answerInvocation =
                answerService.answerWithMetadata(question, retrievedChunks);
        KnowledgeAnswerResponse response = answerInvocation.response();

        long latencyMs = System.currentTimeMillis() - startTime;
        log.info("知识问答完成：status={}，citations={}，latencyMs={}",
                response.status(),
                response.citations() != null ? response.citations().size() : 0,
                latencyMs);

        String retrievedChunkIds = retrievedChunks.stream()
                .map(item -> String.valueOf(item.chunk().id()))
                .collect(java.util.stream.Collectors.joining(","));
        return new QuestionInvocation(
                response,
                retrievedChunkIds.isBlank() ? null : retrievedChunkIds,
                retrievalService.embeddingModelName(),
                latencyMs,
                answerInvocation.promptMetadata(),
                answerInvocation.aiMetadata(),
                answerInvocation.violationCodes(),
                question);
    }

    public record QuestionInvocation(
            KnowledgeAnswerResponse response,
            String retrievedChunkIds,
            String embeddingModel,
            Long latencyMs,
            dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata promptMetadata,
            dev.qcoding.businesscopilot.aicore.AiInvocationMetadata aiMetadata,
            String violationCodes,
            String sanitizedQuestion) {

        public QuestionInvocation(
                KnowledgeAnswerResponse response,
                String retrievedChunkIds,
                String embeddingModel,
                Long latencyMs,
                dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata promptMetadata,
                dev.qcoding.businesscopilot.aicore.AiInvocationMetadata aiMetadata,
                String violationCodes) {
            this(response, retrievedChunkIds, embeddingModel, latencyMs,
                    promptMetadata, aiMetadata, violationCodes, null);
        }
    }
}
