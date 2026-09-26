package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeRetrievalService;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.taskruntime.ContextManifest;
import dev.qcoding.businesscopilot.taskruntime.FailureCategory;
import dev.qcoding.businesscopilot.taskruntime.RuntimeFailureCategories;
import dev.qcoding.businesscopilot.taskruntime.TaskAttempt;
import dev.qcoding.businesscopilot.taskruntime.TaskRun;
import dev.qcoding.businesscopilot.taskruntime.TaskRunBudget;
import dev.qcoding.businesscopilot.taskruntime.TaskRunService;
import dev.qcoding.businesscopilot.taskruntime.TaskStep;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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

    private final TaskRunService taskRunService;

    public KnowledgeQuestionService(KnowledgeRetrievalService retrievalService,
                                     KnowledgeAnswerService answerService,
                                     SensitiveTextMasker sensitiveTextMasker) {
        this(retrievalService, answerService, sensitiveTextMasker, null);
    }

    /** RUN-01/02：装配 TaskRunService 后，检索与答案生成的尝试归属同一运行。 */
    public KnowledgeQuestionService(KnowledgeRetrievalService retrievalService,
                                     KnowledgeAnswerService answerService,
                                     SensitiveTextMasker sensitiveTextMasker,
                                     TaskRunService taskRunService) {
        this.retrievalService = retrievalService;
        this.answerService = answerService;
        this.sensitiveTextMasker = sensitiveTextMasker;
        this.taskRunService = taskRunService;
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

        TaskRun run = null;
        TaskStep step = null;
        if (taskRunService != null) {
            run = taskRunService.startRun("knowledge", "question",
                    "question-" + UUID.randomUUID(),
                    new TaskRunBudget(3, 3, 50_000, Duration.ofMinutes(2)),
                    new ContextManifest(List.of("knowledge:visible-documents:"
                                    + (request.category() == null || request.category().isBlank()
                                    ? "all" : request.category().trim())),
                            "approval:self",
                            Duration.ofHours(1), Instant.now().plusSeconds(3600)));
            step = taskRunService.beginStep(run.runId(), "retrieve-and-answer");
        }

        try {
            QuestionInvocation invocation = askInternal(request, question, startTime,
                    run, step, run != null);
            if (run != null) {
                taskRunService.completeRun(run.runId());
            }
            return invocation;
        } catch (RuntimeException ex) {
            if (run != null && step != null) {
                FailureCategory category = ex instanceof dev.qcoding.businesscopilot.commonweb.api.BusinessException business
                        ? RuntimeFailureCategories.from(business)
                        : FailureCategory.TOOL;
                taskRunService.failStep(step, category, "知识问答步骤失败");
                taskRunService.failRun(run.runId(), category, "知识问答失败");
            }
            throw ex;
        }
    }

    private QuestionInvocation askInternal(@Valid KnowledgeAnswerRequest request, String question,
                                           long startTime, TaskRun run, TaskStep step,
                                           boolean runtimeEnabled) {
        // 1. 检索（证据缺口时执行一次有界补充检索，KNOW-05）
        TaskAttempt retrievalAttempt = runtimeEnabled && step != null
                ? taskRunService.beginToolAttempt(run.runId(), step.stepId(), "knowledge.retrieve")
                : null;
        long retrievalStartedAt = System.currentTimeMillis();
        KnowledgeRetrievalService.RetrievalOutcome retrievalOutcome;
        try {
            retrievalOutcome = retrievalService.retrieveWithGapCheck(question, request.category());
            if (retrievalAttempt != null) {
                taskRunService.finishToolAttempt(run.runId(), retrievalAttempt.attemptId(),
                        System.currentTimeMillis() - retrievalStartedAt,
                        TaskAttempt.Outcome.SUCCESS, null);
            }
        } catch (RuntimeException ex) {
            if (retrievalAttempt != null) {
                FailureCategory category = ex instanceof dev.qcoding.businesscopilot.commonweb.api.BusinessException business
                        ? RuntimeFailureCategories.from(business) : FailureCategory.TOOL;
                taskRunService.finishToolAttempt(run.runId(), retrievalAttempt.attemptId(),
                        System.currentTimeMillis() - retrievalStartedAt,
                        TaskAttempt.Outcome.FAILURE, category);
            }
            throw ex;
        }
        List<RetrievedKnowledgeChunk> retrievedChunks = retrievalOutcome.evidence();

        // 2. 答案生成（含 citation 校验和脱敏）
        KnowledgeAnswerService.AnswerInvocation answerInvocation =
                answerService.answerWithMetadata(question, retrievedChunks,
                        runtimeEnabled && step != null
                                ? taskRunService.aiAttemptObserver(run.runId(), step.stepId())
                                : dev.qcoding.businesscopilot.aicore.AiAttemptObserver.noOp());
        KnowledgeAnswerResponse response = answerInvocation.response();
        if (runtimeEnabled && step != null) {
            taskRunService.completeStep(step,
                    retrievedChunks.stream()
                            .map(item -> "chunk:" + item.chunk().id()).toList(),
                    "答案状态：" + response.status()
                            + (retrievalOutcome.supplementalUsed() ? "（动用补充检索）" : ""));
        }

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
