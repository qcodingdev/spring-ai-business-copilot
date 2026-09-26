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
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateMetricReferenceService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.MetricDictionaryService;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates the SQL generation flow: question → schema → prompt → LLM → parse → guardrails.
 *
 * <p>SQL 生成服务。流程：口径澄清检查 → 获取 schema → 检索已审批指标 → 渲染 prompt → 调用模型
 * → 解析 JSON → guardrails 校验。调用失败要写审计。输出 DTO 不包含内部异常堆栈。</p>
 *
 * <p>DATA-01：生成前检索已审批指标定义并注入上下文，响应记录采用的版本；
 * 停用或未审批的定义不进入生成依据。</p>
 *
 * <p>DATA-02：聚合类问题缺少明确时间范围且没有匹配到已审批指标时，
 * 先返回澄清问题，不把模型猜测当作已确认条件。</p>
 */
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    /** 明确的时间锚点：绝对日期、年/季/月、相对期间。 */
    private static final Pattern TIME_ANCHOR = Pattern.compile(
            "\\d{4}[-/年]\\d{1,2}([-/月]\\d{1,2}日?)?"
                    + "|(19|20)\\d{2}年?(第?[一二三四]季度|Q[1-4])?"
                    + "|\\d{1,2}月(\\d{1,2}[日号])?"
                    + "|上?本?周|上?本?月|上?下?周|今?昨?天|今日|今年|去年|前年"
                    + "|[上下]半?年|第[一二三四]季度|Q[1-4]"
                    + "|近\\d+天|最近\\d+天|过去\\d+天|近\\d+个?月");

    /** 聚合/统计类问题的确定性提示词；命中且无时间锚点时需要澄清。 */
    private static final Pattern AGGREGATION_HINT = Pattern.compile(
            "统计|汇总|多少|数量|总数|合计|总和|平均|均值|占比|比例|环比|同比|趋势|排名|TOP|top|前\\d+|排序|转化率|增长率|客单价");

    private final SchemaContextService schemaContextService;
    private final AiChatService aiChatService;
    private final PromptTemplateService promptTemplateService;
    private final SqlGuardrailService guardrailService;
    private final AuditService auditService;
    private final GuardrailsProperties guardrailsProperties;
    private final SqlConfirmationService confirmationService;
    private final MetricDictionaryService metricDictionaryService;

    public SqlGenerationService(SchemaContextService schemaContextService,
                                 AiChatService aiChatService,
                                 PromptTemplateService promptTemplateService,
                                 SqlGuardrailService guardrailService,
                                 AuditService auditService,
                                 GuardrailsProperties guardrailsProperties,
                                 SqlConfirmationService confirmationService,
                                 MetricDictionaryService metricDictionaryService) {
        this.schemaContextService = schemaContextService;
        this.aiChatService = aiChatService;
        this.promptTemplateService = promptTemplateService;
        this.guardrailService = guardrailService;
        this.auditService = auditService;
        this.guardrailsProperties = guardrailsProperties;
        this.confirmationService = confirmationService;
        this.metricDictionaryService = metricDictionaryService;
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

        // 0. DATA-01/02：检索已审批指标；聚合类问题缺少时间锚点且无指标口径时先澄清，不调用模型。
        List<MetricDictionaryService.ApprovedMetric> matchedMetrics =
                metricDictionaryService.matchingMetrics(request.question());
        if (needsClarification(request.question(), matchedMetrics)) {
            List<String> questions = new java.util.ArrayList<>();
            questions.add("请明确统计的时间范围（例如 2026-08-01 至 2026-08-31，或“本月”“上周”）。");
            if (matchedMetrics.isEmpty()) {
                questions.add("请确认统计对象、单位和去重口径，或指定已审批的指标名称。");
            }
            return SqlGenerationResponse.clarification(requestId, request.question(), questions);
        }

        // 1. Get schema context
        SchemaContext schemaContext = schemaContextService.buildContext();

        // 2. Render prompt（DATA-01：注入已审批指标口径）
        String metricContext = metricDictionaryService.renderPromptContext(matchedMetrics);
        RenderedPrompt prompt = promptTemplateService.renderWithMetadata(
                "data-copilot/sql-generation.st", "v2", Map.of(
                "schemaContext", schemaContext.textSummary(),
                "question", request.question(),
                "currentDate", LocalDate.now().toString(),
                "maxRows", String.valueOf(guardrailsProperties.defaultMaxRows()),
                "metricContext", metricContext,
                "revisionInstruction", request.revisionInstruction() == null
                        ? "None. This is the first generation."
                        : request.revisionInstruction().trim()));

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
                prompt.metadata(), invocationMetadata, SqlGuardrailService.POLICY_VERSION,
                matchedMetrics.stream()
                        .map(metric -> new SqlCandidateMetricReferenceService.MetricReference(
                                metric.metricKey(), metric.version()))
                        .toList());
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
        List<SqlGenerationResponse.AdoptedMetric> adopted = matchedMetrics.stream()
                .map(metric -> new SqlGenerationResponse.AdoptedMetric(
                        metric.metricKey(), metric.displayName(), metric.version()))
                .collect(Collectors.toList());
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
                execCandidate.expiresAt(),
                List.copyOf(adopted),
                List.of(),
                confirmationService.reviewStatus(execCandidate.candidateId()));
    }

    /** DATA-02：聚合类问题缺少时间锚点时必须先澄清；命中已审批指标只减少追问项，不豁免时间范围。 */
    private boolean needsClarification(String question,
                                       List<MetricDictionaryService.ApprovedMetric> matchedMetrics) {
        return AGGREGATION_HINT.matcher(question).find()
                && !TIME_ANCHOR.matcher(question).find();
    }
}
