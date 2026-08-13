package dev.qcoding.businesscopilot.datacopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.aicore.RenderedPrompt;
import dev.qcoding.businesscopilot.audit.AuditEvent;
import dev.qcoding.businesscopilot.audit.AuditEventType;
import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.audit.AuditStatus;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidate;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContext;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import dev.qcoding.businesscopilot.guardrails.SqlValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the SQL generation flow: question → schema → prompt → LLM → parse → guardrails.
 *
 * <p>SQL 生成服务。流程：校验问题 → 获取 schema → 渲染 prompt → 调用模型 → 解析 JSON → guardrails 校验。
 * 调用失败要写审计。输出 DTO 不包含内部异常堆栈。</p>
 */
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    private final SchemaContextService schemaContextService;
    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final SqlGuardrailService guardrailService;
    private final AuditService auditService;
    private final GuardrailsProperties guardrailsProperties;
    private final SqlConfirmationService confirmationService;

    public SqlGenerationService(SchemaContextService schemaContextService,
                                 AiChatService aiChatService,
                                 PromptTemplateService promptTemplateService,
                                 SqlGuardrailService guardrailService,
                                 AuditService auditService,
                                 GuardrailsProperties guardrailsProperties,
                                 SqlConfirmationService confirmationService) {
        this.schemaContextService = schemaContextService;
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.guardrailService = guardrailService;
        this.auditService = auditService;
        this.guardrailsProperties = guardrailsProperties;
        this.confirmationService = confirmationService;
    }

    /**
     * Generate a SQL candidate from a natural language question.
     *
     * @param request validated user request
     * @return generation response with SQL, summary, validation result
     */
    public SqlGenerationResponse generate(SqlGenerationRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long startMs = System.currentTimeMillis();

        // 1. Get schema context
        SchemaContext schemaContext = schemaContextService.buildContext();

        // 2. Render prompt
        RenderedPrompt prompt = promptTemplateService.renderWithMetadata(
                "data-copilot/sql-generation.st", "v2", Map.of(
                "schemaContext", schemaContext.textSummary(),
                "question", request.question(),
                "currentDate", LocalDate.now().toString(),
                "maxRows", String.valueOf(guardrailsProperties.defaultMaxRows())));

        // 3. Call LLM and parse structured output
        GeneratedSqlCandidate candidate;
        AiInvocationMetadata invocationMetadata;
        try {
            AiInvocationResult<GeneratedSqlCandidate> invocation =
                    aiChatService.generatePromptJsonWithMetadata(
                            "data.sql-generation", prompt.content(), GeneratedSqlCandidate.class);
            candidate = invocation.content();
            invocationMetadata = invocation.metadata();
        } catch (BusinessException ex) {
            // 模型调用失败，写审计
            long latencyMs = System.currentTimeMillis() - startMs;
            auditService.record(new AuditEvent(
                    requestId, AuditEventType.QUERY_FAILURE,
                    null, null, null,
                    AuditStatus.MODEL_FAILED, null, false,
                    null, null, aiChatService.modelName(), latencyMs,
                    null, null, aiChatService.providerName(), null,
                    prompt.metadata().name(), prompt.metadata().version(),
                    prompt.metadata().contentHash(), SqlGuardrailService.POLICY_VERSION,
                    null, null, null, null));
            throw ex;
        }

        // 4. Guardrails validation
        SqlValidationResult validationResult = guardrailService.validate(
                candidate.sql(), guardrailsProperties);
        SqlCandidateValidationSummary validationSummary = SqlCandidateValidationSummary.from(validationResult);

        // 5. Build response — guardrails 通过时保存候选并返回 token，失败时不生成可执行 token
        long latencyMs = System.currentTimeMillis() - startMs;
        boolean executable = validationResult.passed();

        // Guardrails 失败时也记录审计
        if (!executable) {
            String violationCodes = validationResult.violations().stream()
                    .map(dev.qcoding.businesscopilot.guardrails.SqlViolation::code)
                    .distinct().collect(java.util.stream.Collectors.joining(","));
            auditService.record(new AuditEvent(
                    requestId, AuditEventType.QUERY_FAILURE,
                    null, null, null,
                    AuditStatus.VALIDATION_FAILED, null, false,
                    null, null, invocationMetadata.modelName(), latencyMs,
                    null, null, invocationMetadata.providerName(),
                    invocationMetadata.providerRequestId(),
                    prompt.metadata().name(), prompt.metadata().version(),
                    prompt.metadata().contentHash(), SqlGuardrailService.POLICY_VERSION,
                    violationCodes, invocationMetadata.inputTokens(),
                    invocationMetadata.outputTokens(), invocationMetadata.finishReason()));

            // guardrails 失败：不生成 token，前端无法据此执行
            return SqlGenerationResponse.notExecutable(
                    requestId, request.question(), candidate.sql(), candidate.summary(),
                    candidate.assumptions() != null ? candidate.assumptions() : List.of(),
                    candidate.warnings() != null ? candidate.warnings() : List.of(),
                    validationSummary);
        }

        // guardrails 通过：保存候选并生成 confirmationToken（携带审计上下文，便于执行阶段写审计）
        SqlCandidate execCandidate = confirmationService.createExecutableCandidate(
                candidate.sql(), requestId, invocationMetadata.modelName(),
                prompt.metadata(), invocationMetadata, SqlGuardrailService.POLICY_VERSION);
        auditService.record(new AuditEvent(
                requestId, AuditEventType.QUERY_CANDIDATE_CREATED,
                null, null, null,
                AuditStatus.CANDIDATE_PENDING, null, false,
                null, null, invocationMetadata.modelName(), latencyMs,
                execCandidate.ownerActorId(), null, invocationMetadata.providerName(),
                invocationMetadata.providerRequestId(),
                prompt.metadata().name(), prompt.metadata().version(),
                prompt.metadata().contentHash(), SqlGuardrailService.POLICY_VERSION,
                null, invocationMetadata.inputTokens(), invocationMetadata.outputTokens(),
                invocationMetadata.finishReason()));
        return new SqlGenerationResponse(
                requestId,
                request.question(),
                candidate.sql(),
                candidate.summary(),
                candidate.assumptions() != null ? candidate.assumptions() : List.of(),
                candidate.warnings() != null ? candidate.warnings() : List.of(),
                validationSummary,
                true,
                execCandidate.candidateId(),
                execCandidate.confirmationToken(),
                execCandidate.expiresAt());
    }
}
