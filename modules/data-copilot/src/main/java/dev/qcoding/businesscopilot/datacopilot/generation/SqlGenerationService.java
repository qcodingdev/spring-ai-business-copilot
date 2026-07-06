package dev.qcoding.businesscopilot.datacopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.audit.AuditEvent;
import dev.qcoding.businesscopilot.audit.AuditEventType;
import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.audit.AuditStatus;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContext;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import dev.qcoding.businesscopilot.guardrails.SqlValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the SQL generation flow: question → schema → prompt → LLM → parse → guardrails.
 *
 * <p>SQL 生成服务。流程：校验问题 → 获取 schema → 渲染 prompt → 调用模型 → 解析 JSON → guardrails 校验。
 * 调用失败要写审计。输出 DTO 不包含内部异常堆栈。</p>
 */
@Service
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    private final SchemaContextService schemaContextService;
    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final SqlGuardrailService guardrailService;
    private final AuditService auditService;
    private final GuardrailsProperties guardrailsProperties;

    public SqlGenerationService(SchemaContextService schemaContextService,
                                 AiChatService aiChatService,
                                 PromptTemplateService promptTemplateService,
                                 SqlGuardrailService guardrailService,
                                 AuditService auditService,
                                 GuardrailsProperties guardrailsProperties) {
        this.schemaContextService = schemaContextService;
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.guardrailService = guardrailService;
        this.auditService = auditService;
        this.guardrailsProperties = guardrailsProperties;
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
        String prompt = promptTemplateService.render("data-copilot/sql-generation.st", Map.of(
                "schemaContext", schemaContext.textSummary(),
                "question", request.question(),
                "maxRows", String.valueOf(guardrailsProperties.defaultMaxRows())));

        // 3. Call LLM and parse structured output
        GeneratedSqlCandidate candidate;
        try {
            candidate = aiChatService.generateJson(prompt, GeneratedSqlCandidate.class);
        } catch (BusinessException ex) {
            // 模型调用失败，写审计
            long latencyMs = System.currentTimeMillis() - startMs;
            auditService.record(new AuditEvent(
                    requestId, AuditEventType.QUERY_FAILURE,
                    request.question(), null, null,
                    AuditStatus.MODEL_FAILED, null, false,
                    null, ex.getMessage(), aiChatService.modelName(), latencyMs));
            throw ex;
        }

        // 4. Guardrails validation
        SqlValidationResult validationResult = guardrailService.validate(
                candidate.sql(), guardrailsProperties);
        SqlCandidateValidationSummary validationSummary = SqlCandidateValidationSummary.from(validationResult);

        // 5. Build response
        long latencyMs = System.currentTimeMillis() - startMs;
        boolean executable = validationResult.passed();

        // Guardrails 失败时也记录审计
        if (!executable) {
            String violationDetails = String.join("; ", validationSummary.violations());
            auditService.record(new AuditEvent(
                    requestId, AuditEventType.QUERY_FAILURE,
                    request.question(), candidate.sql(), null,
                    AuditStatus.VALIDATION_FAILED, violationDetails, false,
                    null, null, aiChatService.modelName(), latencyMs));
        }

        return new SqlGenerationResponse(
                requestId,
                request.question(),
                candidate.sql(),
                candidate.summary(),
                candidate.assumptions() != null ? candidate.assumptions() : List.of(),
                candidate.warnings() != null ? candidate.warnings() : List.of(),
                validationSummary,
                executable);
    }
}
