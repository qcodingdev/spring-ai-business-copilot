package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiAttemptObserver;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateProvider;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Persistent evaluation control plane: datasets, immutable versions, cases, asynchronous runs,
 * case results, gate decisions and auditable reports. It never starts Maven or arbitrary scripts.
 */
@Service
public class EvaluationManagementService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationManagementService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actorProvider;
    private final AiChatService aiChatService;
    private final PromptGovernanceService promptService;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transaction;
    private final AtomicBoolean catalogSeeded = new AtomicBoolean(false);

    @Value("${business-copilot.evaluation.run-stale-after:PT15M}")
    private Duration runStaleAfter = Duration.ofMinutes(15);

    public EvaluationManagementService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CurrentActorProvider actorProvider,
            AiChatService aiChatService,
            PromptGovernanceService promptService,
            @Qualifier("evaluationTaskExecutor") TaskExecutor taskExecutor,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.actorProvider = actorProvider;
        this.aiChatService = aiChatService;
        this.promptService = promptService;
        this.taskExecutor = taskExecutor;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<DatasetView> datasets() {
        requireAuthenticated();
        ensureCatalogSeeded();
        return jdbcTemplate.query("""
                SELECT id, dataset_key, module_key, name_zh, name_en,
                       description_zh, description_en, status, owner_actor_id,
                       created_at, updated_at
                FROM evaluation_datasets ORDER BY status, module_key, dataset_key
                """, (rs, rowNum) -> new DatasetView(
                        rs.getLong("id"), rs.getString("dataset_key"), rs.getString("module_key"),
                        rs.getString("name_zh"), rs.getString("name_en"),
                        rs.getString("description_zh"), rs.getString("description_en"),
                        rs.getString("status"), rs.getString("owner_actor_id"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                        versions(rs.getLong("id"))));
    }

    @Transactional
    public DatasetView createDataset(DatasetCommand command) {
        CurrentActor actor = requireOperatorOrAdmin();
        String key = normalizeKey(command.datasetKey());
        long datasetId;
        try {
            datasetId = jdbcTemplate.queryForObject("""
                    INSERT INTO evaluation_datasets (
                        dataset_key, module_key, name_zh, name_en,
                        description_zh, description_en, owner_actor_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, key, normalizeModule(command.moduleKey()),
                    normalizeRequired(command.nameZh()), normalizeRequired(command.nameEn()),
                    normalizeOptional(command.descriptionZh()), normalizeOptional(command.descriptionEn()),
                    actor.actorId());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "评测集标识已存在。");
        }
        jdbcTemplate.update("""
                INSERT INTO evaluation_dataset_versions (
                    dataset_id, version_number, status, change_note, created_by
                ) VALUES (?, 1, 'DRAFT', ?, ?)
                """, datasetId, "Initial version", actor.actorId());
        return dataset(datasetId);
    }

    @Transactional
    public DatasetView archiveDataset(long datasetId) {
        CurrentActor actor = requireAdmin();
        int updated = jdbcTemplate.update("""
                UPDATE evaluation_datasets SET status = 'ARCHIVED', updated_at = now()
                WHERE id = ? AND status = 'ACTIVE'
                """, datasetId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return dataset(datasetId);
    }

    @Transactional
    public VersionView cloneVersion(long sourceVersionId, String changeNote) {
        CurrentActor actor = requireOperatorOrAdmin();
        VersionView source = versionForUpdate(sourceVersionId);
        requireDatasetActive(source.datasetId());
        Integer next = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM evaluation_dataset_versions WHERE dataset_id = ?
                """, Integer.class, source.datasetId());
        long versionId = jdbcTemplate.queryForObject("""
                INSERT INTO evaluation_dataset_versions (
                    dataset_id, version_number, status, change_note, created_by
                ) VALUES (?, ?, 'DRAFT', ?, ?) RETURNING id
                """, Long.class, source.datasetId(), next, normalizeRequired(changeNote), actor.actorId());
        jdbcTemplate.update("""
                INSERT INTO evaluation_cases (
                    version_id, case_key, title_zh, title_en, execution_type,
                    prompt_key, variables_json, expected_json, forbidden_json,
                    critical, enabled, max_latency_ms, max_model_calls
                ) SELECT ?, case_key, title_zh, title_en, execution_type,
                         prompt_key, variables_json, expected_json, forbidden_json,
                         critical, enabled, max_latency_ms, max_model_calls
                  FROM evaluation_cases WHERE version_id = ?
                """, versionId, sourceVersionId);
        return version(versionId);
    }

    @Transactional
    public CaseView saveCase(long versionId, Long caseId, CaseCommand command) {
        CurrentActor actor = requireOperatorOrAdmin();
        VersionView version = versionForUpdate(versionId);
        requireDraftOwner(version, actor);
        validateCase(command);
        if (caseId == null) {
            long id;
            try {
                id = jdbcTemplate.queryForObject("""
                        INSERT INTO evaluation_cases (
                            version_id, case_key, title_zh, title_en, execution_type,
                            prompt_key, variables_json, expected_json, forbidden_json,
                            critical, enabled, max_latency_ms, max_model_calls
                        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                                  ?, ?, ?, ?) RETURNING id
                        """, Long.class, versionId, normalizeKey(command.caseKey()),
                        normalizeRequired(command.titleZh()), normalizeRequired(command.titleEn()),
                        command.executionType().name(), normalizeOptional(command.promptKey()),
                        json(command.variables()), json(command.expected()), json(command.forbidden()),
                        command.critical(), command.enabled(), command.maxLatencyMs(),
                        command.maxModelCalls());
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "用例标识在当前版本中已存在。");
            }
            return evaluationCase(id);
        }
        int updated = jdbcTemplate.update("""
                UPDATE evaluation_cases SET
                    case_key = ?, title_zh = ?, title_en = ?, execution_type = ?,
                    prompt_key = ?, variables_json = ?::jsonb, expected_json = ?::jsonb,
                    forbidden_json = ?::jsonb, critical = ?, enabled = ?,
                    max_latency_ms = ?, max_model_calls = ?, updated_at = now()
                WHERE id = ? AND version_id = ?
                """, normalizeKey(command.caseKey()), normalizeRequired(command.titleZh()),
                normalizeRequired(command.titleEn()), command.executionType().name(),
                normalizeOptional(command.promptKey()), json(command.variables()),
                json(command.expected()), json(command.forbidden()), command.critical(),
                command.enabled(), command.maxLatencyMs(), command.maxModelCalls(),
                caseId, versionId);
        if (updated != 1) throw new BusinessException(ErrorCode.NOT_FOUND);
        return evaluationCase(caseId);
    }

    @Transactional
    public List<CaseView> importCases(long versionId, List<CaseCommand> cases) {
        if (cases == null || cases.isEmpty() || cases.size() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        for (CaseCommand command : cases) saveCase(versionId, null, command);
        return cases(versionId);
    }

    @Transactional
    public CaseView setCaseEnabled(long versionId, long caseId, boolean enabled) {
        CurrentActor actor = requireOperatorOrAdmin();
        VersionView version = versionForUpdate(versionId);
        requireDraftOwner(version, actor);
        int updated = jdbcTemplate.update("""
                UPDATE evaluation_cases SET enabled = ?, updated_at = now()
                WHERE id = ? AND version_id = ?
                """, enabled, caseId, versionId);
        if (updated != 1) throw new BusinessException(ErrorCode.NOT_FOUND);
        return evaluationCase(caseId);
    }

    @Transactional
    public VersionView submitVersion(long versionId) {
        CurrentActor actor = requireOperatorOrAdmin();
        VersionView version = versionForUpdate(versionId);
        requireDraftOwner(version, actor);
        List<CaseView> enabled = version.cases().stream().filter(CaseView::enabled).toList();
        if (enabled.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "评测集版本至少需要一个启用用例。");
        String hash = contentHash(enabled);
        int updated = jdbcTemplate.update("""
                UPDATE evaluation_dataset_versions
                SET status = 'IN_REVIEW', content_hash = ?, submitted_by = ?, submitted_at = now()
                WHERE id = ? AND status = 'DRAFT'
                """, hash, actor.actorId(), versionId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return version(versionId);
    }

    @Transactional
    public VersionView reviewVersion(long versionId, boolean approve, String note) {
        CurrentActor actor = requireReviewerOrAdmin();
        VersionView version = versionForUpdate(versionId);
        if (!"IN_REVIEW".equals(version.status())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        if (!actor.hasRole(BusinessRole.ADMIN) && actor.actorId().equals(version.createdBy())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "评测集创建者不能审核自己的版本。");
        }
        String target = approve ? "REVIEWED" : "DRAFT";
        int updated = jdbcTemplate.update("""
                UPDATE evaluation_dataset_versions
                SET status = ?, reviewed_by = ?, reviewed_at = now(),
                    change_note = CASE WHEN ? THEN change_note ELSE CONCAT(change_note, E'\nREJECTED: ', ?) END
                WHERE id = ? AND status = 'IN_REVIEW'
                """, target, actor.actorId(), approve, normalizeRequired(note), versionId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return version(versionId);
    }

    @Transactional
    public VersionView publishVersion(long versionId) {
        requireAdmin();
        VersionView version = versionForUpdate(versionId);
        if (!"REVIEWED".equals(version.status())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        jdbcTemplate.update("""
                UPDATE evaluation_dataset_versions SET status = 'RETIRED'
                WHERE dataset_id = ? AND status = 'PUBLISHED'
                """, version.datasetId());
        int updated = jdbcTemplate.update("""
                UPDATE evaluation_dataset_versions SET status = 'PUBLISHED', published_at = now()
                WHERE id = ? AND status = 'REVIEWED'
                """, versionId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return version(versionId);
    }

    public List<GatePolicy> gatePolicies() {
        requireAuthenticated();
        return jdbcTemplate.query("""
                SELECT module_key, minimum_pass_rate, require_critical_pass,
                       maximum_average_latency, maximum_total_tokens, updated_by, updated_at
                FROM evaluation_gate_policies ORDER BY module_key
                """, (rs, rowNum) -> new GatePolicy(
                        rs.getString("module_key"), rs.getBigDecimal("minimum_pass_rate").doubleValue(),
                        rs.getBoolean("require_critical_pass"),
                        rs.getObject("maximum_average_latency", Long.class),
                        rs.getObject("maximum_total_tokens", Long.class),
                        rs.getString("updated_by"), instant(rs.getTimestamp("updated_at"))));
    }

    @Transactional
    public GatePolicy saveGatePolicy(GatePolicyCommand command) {
        CurrentActor actor = requireAdmin();
        if (command.minimumPassRate() < 0 || command.minimumPassRate() > 100
                || (command.maximumAverageLatency() != null && command.maximumAverageLatency() <= 0)
                || (command.maximumTotalTokens() != null && command.maximumTotalTokens() <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        String module = normalizeModule(command.moduleKey());
        jdbcTemplate.update("""
                INSERT INTO evaluation_gate_policies (
                    module_key, minimum_pass_rate, require_critical_pass,
                    maximum_average_latency, maximum_total_tokens, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (module_key) DO UPDATE SET
                    minimum_pass_rate = EXCLUDED.minimum_pass_rate,
                    require_critical_pass = EXCLUDED.require_critical_pass,
                    maximum_average_latency = EXCLUDED.maximum_average_latency,
                    maximum_total_tokens = EXCLUDED.maximum_total_tokens,
                    updated_by = EXCLUDED.updated_by, updated_at = now()
                """, module, command.minimumPassRate(), command.requireCriticalPass(),
                command.maximumAverageLatency(), command.maximumTotalTokens(), actor.actorId());
        return gatePolicies().stream().filter(item -> item.moduleKey().equals(module)).findFirst()
                .orElseThrow();
    }

    public RunView startRun(RunCommand command) {
        CurrentActor actor = requireOperatorOrAdmin();
        VersionView version = version(command.versionId());
        requireDatasetActive(version.datasetId());
        if (!"PUBLISHED".equals(version.status())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "只能运行已发布的评测集版本。");
        }
        if (command.promptVersionId() != null) {
            PromptGovernanceService.VersionView prompt = promptVersion(command.promptVersionId());
            if (!Set.of("REVIEWED", "PUBLISHED").contains(prompt.status())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "只能评测已审核或已发布的 Prompt 版本。");
            }
        }
        if (command.promptVersionId() != null && version.cases().stream().noneMatch(item -> item.enabled()
                && item.executionType() == ExecutionType.PROMPT
                && resolvePrompt(command.promptVersionId(), item.promptKey()) != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "评测集必须包含目标 Prompt 的启用模型用例。");
        }
        String idempotencyKey = normalizeRequired(command.idempotencyKey());
        UUID runId = UUID.randomUUID();
        try {
            int inserted = jdbcTemplate.update("""
                    INSERT INTO evaluation_runs (
                        id, version_id, prompt_version_id, environment, status,
                        gate_decision, idempotency_key, requested_by
                    )
                    SELECT ?, version.id, ?, ?, 'QUEUED', 'NOT_VERIFIED', ?, ?
                    FROM evaluation_dataset_versions version
                    JOIN evaluation_datasets dataset ON dataset.id = version.dataset_id
                    WHERE version.id = ? AND version.status = 'PUBLISHED' AND dataset.status = 'ACTIVE'
                    """, runId, command.promptVersionId(), command.environment().name(),
                    idempotencyKey, actor.actorId(), command.versionId());
            if (inserted != 1) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "评测集已归档或版本已停止发布，不能启动评测。");
            }
        } catch (DuplicateKeyException ex) {
            RunView existing = jdbcTemplate.query("""
                    SELECT id FROM evaluation_runs WHERE requested_by = ? AND idempotency_key = ?
                    """, (rs, rowNum) -> run(rs.getObject("id", UUID.class)), actor.actorId(),
                    idempotencyKey).getFirst();
            if (existing.versionId() != command.versionId()
                    || !java.util.Objects.equals(existing.promptVersionId(), command.promptVersionId())
                    || !existing.environment().equals(command.environment().name())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "幂等标识已用于不同的评测请求。");
            }
            return existing;
        }
        String locale = BusinessRequestContextHolder.currentLocale();
        try {
            taskExecutor.execute(() -> executeRun(runId, actor.actorId(), locale));
        } catch (RejectedExecutionException ex) {
            jdbcTemplate.update("""
                    UPDATE evaluation_runs SET status = 'FAILED', gate_decision = 'NOT_VERIFIED',
                        error_category = 'DISPATCH_REJECTED', finished_at = now()
                    WHERE id = ? AND status = 'QUEUED'
                    """, runId);
        }
        return run(runId);
    }

    @Scheduled(
            fixedDelayString = "${business-copilot.evaluation.recovery-scan-delay:PT1M}",
            initialDelayString = "${business-copilot.evaluation.recovery-scan-initial-delay:PT1M}")
    public void reconcileInterruptedRuns() {
        int reconciled = reconcileInterruptedRuns(runStaleAfter);
        if (reconciled > 0) {
            log.warn("已收敛进程中断遗留的评测运行：count={}", reconciled);
        }
    }

    int reconcileInterruptedRuns(Duration staleAfter) {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        return jdbcTemplate.update("""
                UPDATE evaluation_runs
                SET status = 'FAILED', gate_decision = 'NOT_VERIFIED',
                    error_category = 'PROCESS_INTERRUPTED', finished_at = now()
                WHERE status IN ('QUEUED', 'RUNNING')
                  AND COALESCE(heartbeat_at, started_at, created_at) < ?
                """, Timestamp.from(Instant.now().minus(staleAfter)));
    }

    public List<RunView> runs() {
        CurrentActor actor = requireAuthenticated();
        boolean privileged = actor.hasRole(BusinessRole.ADMIN) || actor.hasRole(BusinessRole.REVIEWER);
        return jdbcTemplate.query("""
                SELECT id FROM evaluation_runs
                WHERE (? OR requested_by = ?)
                ORDER BY created_at DESC LIMIT 100
                """, (rs, rowNum) -> run(rs.getObject("id", UUID.class)), privileged, actor.actorId());
    }

    public RunView run(UUID runId) {
        CurrentActor actor = requireAuthenticated();
        RunView run = loadRun(runId);
        if (actor.authenticated()
                && !actor.hasRole(BusinessRole.ADMIN) && !actor.hasRole(BusinessRole.REVIEWER)
                && !actor.actorId().equals(run.requestedBy())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return run;
    }

    @Transactional
    public RunView cancelRun(UUID runId) {
        CurrentActor actor = requireAuthenticated();
        RunView run = loadRun(runId);
        if (!actor.hasRole(BusinessRole.ADMIN) && !actor.actorId().equals(run.requestedBy())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        int updated = jdbcTemplate.update("""
                UPDATE evaluation_runs
                SET status = 'CANCELED', gate_decision = 'NOT_VERIFIED', finished_at = now()
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, runId);
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return loadRun(runId);
    }

    @Transactional
    public RunView recordExternalResults(UUID runId, List<ExternalResultCommand> results) {
        requireAdmin();
        lockRun(runId);
        RunView run = loadRun(runId);
        if ("CANCELED".equals(run.status())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        if (results == null || results.isEmpty() || results.size() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Map<String, CaseView> byKey = new LinkedHashMap<>();
        version(run.versionId()).cases().forEach(item -> byKey.put(item.caseKey(), item));
        for (ExternalResultCommand result : results) {
            if (result == null || result.status() == null || result.caseKey() == null
                    || result.caseKey().isBlank() || result.evidenceReference() == null
                    || result.evidenceReference().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "外部结果必须包含用例标识、状态和可追溯证据引用。");
            }
            CaseView evaluationCase = byKey.get(result.caseKey());
            if (evaluationCase == null || !evaluationCase.enabled()
                    || evaluationCase.executionType() != ExecutionType.EXTERNAL) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "外部结果包含未知、已停用或非外部执行用例。");
            }
            if ((result.latencyMs() != null && result.latencyMs() < 0)
                    || (result.inputTokens() != null && result.inputTokens() < 0)
                    || (result.outputTokens() != null && result.outputTokens() < 0)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "耗时与用量不能为负数。");
            }
            jdbcTemplate.update("""
                    INSERT INTO evaluation_case_results (
                        run_id, case_id, status, output_hash, output_summary, score,
                        latency_ms, input_tokens, output_tokens, failure_reason, assertion_results
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (run_id, case_id) DO UPDATE SET
                        status = EXCLUDED.status, output_hash = EXCLUDED.output_hash,
                        output_summary = EXCLUDED.output_summary, score = EXCLUDED.score,
                        latency_ms = EXCLUDED.latency_ms, input_tokens = EXCLUDED.input_tokens,
                        output_tokens = EXCLUDED.output_tokens,
                        failure_reason = EXCLUDED.failure_reason,
                        assertion_results = EXCLUDED.assertion_results
                    """, runId, evaluationCase.id(), result.status().name(),
                    normalizeOptional(result.outputHash()), bounded(result.summary(), 1000),
                    result.status() == ResultStatus.PASSED ? 100 : 0, result.latencyMs(),
                    result.inputTokens(), result.outputTokens(), bounded(result.failureReason(), 1000),
                    json(List.of(Map.of("kind", "EXTERNAL_HARNESS", "passed",
                            result.status() == ResultStatus.PASSED,
                            "evidence", result.evidenceReference() == null ? "" : result.evidenceReference()))));
        }
        finalizeRun(runId, false);
        return loadRun(runId);
    }

    public String markdownReport(UUID runId) {
        RunView run = run(runId);
        StringBuilder report = new StringBuilder();
        report.append("# Evaluation Gate Report\n\n")
                .append("- Run: `").append(run.id()).append("`\n")
                .append("- Dataset version: ").append(run.versionId()).append("\n")
                .append("- Environment: ").append(run.environment()).append("\n")
                .append("- Status: ").append(run.status()).append("\n")
                .append("- Gate decision: **").append(run.gateDecision()).append("**\n")
                .append("- Passed / failed / not verified: ")
                .append(run.passedCases()).append(" / ").append(run.failedCases()).append(" / ")
                .append(run.notVerifiedCases()).append("\n")
                .append("- Pass rate: ").append(run.passRate() == null ? "—" : run.passRate() + "%").append("\n\n")
                .append("## Case results\n\n");
        for (CaseResultView result : run.results()) {
            report.append("- ").append(result.caseKey()).append(" · ")
                    .append(result.title()).append(" · **").append(result.status()).append("**");
            if (result.failureReason() != null) report.append(" · ").append(result.failureReason());
            report.append("\n");
        }
        report.append("\n> NOT_VERIFIED never counts as passed. This report does not publish a release.\n");
        return report.toString();
    }

    private void executeRun(UUID runId, String actorId, String locale) {
        BusinessRequestContextHolder.set(new BusinessRequestContext(
                "evaluation-" + runId, actorId, Set.of("ADMIN"), locale));
        try {
            int claimed = jdbcTemplate.update("""
                    UPDATE evaluation_runs
                    SET status = 'RUNNING', started_at = now(), heartbeat_at = now()
                    WHERE id = ? AND status = 'QUEUED'
                    """, runId);
            if (claimed != 1) return;
            RunView run = loadRun(runId);
            for (CaseView evaluationCase : version(run.versionId()).cases()) {
                if (!isWorkerActive(runId)) return;
                if (!evaluationCase.enabled()) continue;
                touchWorkerHeartbeat(runId);
                CaseExecution result = executeCase(run, evaluationCase);
                if (!isWorkerActive(runId)) return;
                saveCaseResult(runId, evaluationCase, result);
            }
            if (isWorkerActive(runId)) finalizeRun(runId, true);
        } catch (RuntimeException ex) {
            jdbcTemplate.update("""
                    UPDATE evaluation_runs
                    SET status = 'FAILED', gate_decision = 'BLOCK_RELEASE',
                        error_category = ?, finished_at = now()
                    WHERE id = ? AND status = 'RUNNING'
                    """, bounded(ex.getClass().getSimpleName(), 100), runId);
        } finally {
            BusinessRequestContextHolder.clear();
        }
    }

    private CaseExecution executeCase(RunView run, CaseView evaluationCase) {
        if (evaluationCase.executionType() == ExecutionType.EXTERNAL) {
            return CaseExecution.notVerified("需要独立 Harness/CI 回传原始结果，未执行不能计为通过。");
        }
        if (!aiChatService.isModelEnabled()) {
            return CaseExecution.notVerified("当前环境未配置可用模型，真实模型评测未验证。");
        }
        String template = resolvePrompt(run.promptVersionId(), evaluationCase.promptKey());
        if (template == null) return CaseExecution.notVerified("未找到可评测的 Prompt 版本。");
        String prompt = substitute(template, stringMap(evaluationCase.variables()));
        AtomicBoolean budgetExceeded = new AtomicBoolean();
        AiAttemptObserver observer = new AiAttemptObserver() {
            private int dispatched;

            @Override
            public String beforeAttempt(String operation, String provider, String model, int estimatedTokens) {
                if (!isWorkerActive(run.id())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
                touchWorkerHeartbeat(run.id());
                if (evaluationCase.maxModelCalls() != null && dispatched >= evaluationCase.maxModelCalls()) {
                    budgetExceeded.set(true);
                    throw new BusinessException(ErrorCode.STATE_CONFLICT);
                }
                return Integer.toString(++dispatched);
            }

            @Override
            public void afterAttempt(String attemptId, AiInvocationMetadata metadata, Throwable failure) {
                // AiChatService returns cumulative metadata for all authorized attempts.
            }
        };
        try {
            AiInvocationResult<String> invocation = aiChatService.generateTextWithMetadata(
                    "evaluation.prompt", prompt, observer);
            return evaluateOutput(evaluationCase, invocation.content(), invocation.metadata());
        } catch (BusinessException ex) {
            if (budgetExceeded.get()) return CaseExecution.failed("模型调用次数达到用例上限，已阻止额外调用。");
            if (ex.errorCode() == ErrorCode.AI_MODEL_ERROR) {
                return CaseExecution.notVerified("模型供应商调用不可用，结果未验证。");
            }
            return CaseExecution.failed("评测执行失败：" + ex.errorCode().code());
        } catch (RuntimeException ex) {
            return CaseExecution.failed("评测执行失败：" + ex.getClass().getSimpleName());
        }
    }

    private CaseExecution evaluateOutput(CaseView evaluationCase, String output,
                                         AiInvocationMetadata metadata) {
        if (output == null || output.isBlank()) return CaseExecution.failed("模型输出为空。");
        List<Map<String, Object>> assertions = new ArrayList<>();
        int total = 0;
        int passed = 0;
        for (String expected : stringList(evaluationCase.expected().get("contains"))) {
            boolean holds = output.contains(expected);
            assertions.add(assertion("CONTAINS", expected, holds)); total++; if (holds) passed++;
        }
        List<String> forbidden = new ArrayList<>(evaluationCase.forbidden());
        forbidden.addAll(stringList(evaluationCase.expected().get("notContains")));
        for (String value : forbidden) {
            boolean holds = !output.contains(value);
            assertions.add(assertion("NOT_CONTAINS", value, holds)); total++; if (holds) passed++;
        }
        Object regex = evaluationCase.expected().get("regex");
        if (regex instanceof String pattern && !pattern.isBlank()) {
            boolean holds;
            try { holds = Pattern.compile(pattern, Pattern.DOTALL).matcher(output).find(); }
            catch (PatternSyntaxException ex) { holds = false; }
            assertions.add(assertion("REGEX", pattern, holds)); total++; if (holds) passed++;
        }
        Object jsonValid = evaluationCase.expected().get("jsonValid");
        if (Boolean.TRUE.equals(jsonValid)) {
            boolean holds;
            try { objectMapper.readTree(output); holds = true; }
            catch (JacksonException ex) { holds = false; }
            assertions.add(assertion("JSON_VALID", "valid JSON", holds)); total++; if (holds) passed++;
        }
        Integer minLength = integer(evaluationCase.expected().get("minLength"));
        if (minLength != null) {
            boolean holds = output.length() >= minLength;
            assertions.add(assertion("MIN_LENGTH", String.valueOf(minLength), holds)); total++; if (holds) passed++;
        }
        Integer maxLength = integer(evaluationCase.expected().get("maxLength"));
        if (maxLength != null) {
            boolean holds = output.length() <= maxLength;
            assertions.add(assertion("MAX_LENGTH", String.valueOf(maxLength), holds)); total++; if (holds) passed++;
        }
        if (evaluationCase.maxLatencyMs() != null) {
            boolean holds = metadata != null && metadata.latencyMs() <= evaluationCase.maxLatencyMs();
            assertions.add(assertion("LATENCY", String.valueOf(evaluationCase.maxLatencyMs()), holds));
            total++; if (holds) passed++;
        }
        if (total == 0) return CaseExecution.notVerified("用例没有可执行断言。");
        boolean allPassed = passed == total;
        Integer inputTokens = metadata != null ? metadata.inputTokens() : null;
        Integer outputTokens = metadata != null ? metadata.outputTokens() : null;
        return new CaseExecution(allPassed ? ResultStatus.PASSED : ResultStatus.FAILED,
                sha256(output), bounded(output.replaceAll("\\s+", " "), 500),
                total == 0 ? 0 : passed * 100.0 / total,
                metadata != null ? metadata.latencyMs() : null,
                inputTokens, outputTokens, allPassed ? null : "一个或多个断言未通过。", assertions);
    }

    private void saveCaseResult(UUID runId, CaseView evaluationCase, CaseExecution result) {
        transaction.executeWithoutResult(ignored -> {
            lockRun(runId);
            if (!isWorkerActive(runId)) return;
            jdbcTemplate.update("""
                INSERT INTO evaluation_case_results (
                    run_id, case_id, status, output_hash, output_summary, score,
                    latency_ms, input_tokens, output_tokens, failure_reason, assertion_results
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (run_id, case_id) DO NOTHING
                """, runId, evaluationCase.id(), result.status().name(), result.outputHash(),
                result.outputSummary(), result.score(), result.latencyMs(), result.inputTokens(),
                result.outputTokens(), result.failureReason(), json(result.assertions()));
        });
    }

    private void finalizeRun(UUID runId, boolean workerFinished) {
        transaction.executeWithoutResult(ignored -> {
            lockRun(runId);
            RunView current = loadRun(runId);
            if ("CANCELED".equals(current.status())) return;
            boolean pendingWorker = !workerFinished && Set.of("QUEUED", "RUNNING").contains(current.status())
                    && version(current.versionId()).cases().stream().anyMatch(item -> item.enabled()
                        && item.executionType() == ExecutionType.PROMPT);
            finalizeRunLocked(runId, pendingWorker ? current.status() : null);
        });
    }

    private void lockRun(UUID runId) {
        if (jdbcTemplate.query("SELECT id FROM evaluation_runs WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject(1, UUID.class), runId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private void finalizeRunLocked(UUID runId, String pendingStatus) {
        Aggregate aggregate = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE result.status = 'PASSED') AS passed,
                       COUNT(*) FILTER (WHERE result.status = 'FAILED') AS failed,
                       COUNT(*) FILTER (WHERE result.id IS NULL OR result.status = 'NOT_VERIFIED') AS not_verified,
                       AVG(result.latency_ms) FILTER (WHERE result.latency_ms IS NOT NULL) AS avg_latency,
                       CASE WHEN COUNT(*) FILTER (WHERE result.input_tokens IS NULL OR result.output_tokens IS NULL) > 0
                            THEN NULL ELSE SUM(result.input_tokens::bigint + result.output_tokens::bigint) END AS tokens,
                       COUNT(*) FILTER (WHERE evaluation_case.critical = TRUE AND result.status IS DISTINCT FROM 'PASSED') AS critical_failed,
                       COUNT(*) FILTER (WHERE result.status = 'PASSED' AND result.latency_ms IS NULL) AS unknown_latency
                FROM evaluation_runs run
                JOIN evaluation_cases evaluation_case ON evaluation_case.version_id = run.version_id AND evaluation_case.enabled
                LEFT JOIN evaluation_case_results result ON result.case_id = evaluation_case.id AND result.run_id = run.id
                WHERE run.id = ?
                """, (rs, rowNum) -> {
                    java.math.BigDecimal average = rs.getBigDecimal("avg_latency");
                    java.math.BigDecimal tokens = rs.getBigDecimal("tokens");
                    return new Aggregate(
                            rs.getInt("total"), rs.getInt("passed"), rs.getInt("failed"),
                            rs.getInt("not_verified"), average == null ? null : average.longValue(),
                            tokens == null ? null : tokens.longValueExact(),
                            rs.getInt("critical_failed"), rs.getInt("unknown_latency"));
                }, runId);
        if (aggregate == null || aggregate.total() == 0) {
            aggregate = new Aggregate(0, 0, 0, 0, null, null, 0, 0);
        }
        String module = jdbcTemplate.queryForObject("""
                SELECT dataset.module_key FROM evaluation_runs run
                JOIN evaluation_dataset_versions version ON version.id = run.version_id
                JOIN evaluation_datasets dataset ON dataset.id = version.dataset_id
                WHERE run.id = ?
                """, String.class, runId);
        GatePolicy policy = gatePolicies().stream().filter(item -> item.moduleKey().equals(module))
                .findFirst().orElseThrow();
        double passRate = aggregate.total() == 0 ? 0 : aggregate.passed() * 100.0 / aggregate.total();
        List<String> blockers = new ArrayList<>();
        if (aggregate.total() == 0) blockers.add("没有执行结果");
        if (aggregate.notVerified() > 0) blockers.add("存在未验证用例");
        boolean unknownUsage = policy.maximumTotalTokens() != null && aggregate.totalTokens() == null;
        boolean unknownLatency = policy.maximumAverageLatency() != null && aggregate.unknownLatency() > 0;
        if (unknownUsage) blockers.add("Token 用量未知，无法核验预算门槛");
        if (unknownLatency) blockers.add("存在耗时未知的通过结果，无法核验耗时门槛");
        if (passRate < policy.minimumPassRate()) blockers.add("通过率低于门槛");
        if (policy.requireCriticalPass() && aggregate.criticalFailed() > 0) blockers.add("关键用例未全部通过");
        if (policy.maximumAverageLatency() != null && aggregate.averageLatency() != null
                && aggregate.averageLatency() > policy.maximumAverageLatency()) blockers.add("平均耗时超过门槛");
        if (policy.maximumTotalTokens() != null && aggregate.totalTokens() != null
                && aggregate.totalTokens() > policy.maximumTotalTokens()) {
            blockers.add("Token 使用超过门槛");
        }
        String status;
        String decision;
        if (aggregate.total() == 0 || aggregate.notVerified() > 0 || unknownUsage || unknownLatency) {
            status = "NOT_VERIFIED"; decision = "NOT_VERIFIED";
        } else if (blockers.isEmpty()) {
            status = "PASSED"; decision = "ALLOW_RELEASE";
        } else {
            status = "FAILED"; decision = "BLOCK_RELEASE";
        }
        if (pendingStatus != null) {
            status = pendingStatus;
            decision = "NOT_VERIFIED";
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("status", status);
        report.put("gateDecision", decision);
        report.put("total", aggregate.total());
        report.put("passed", aggregate.passed());
        report.put("failed", aggregate.failed());
        report.put("notVerified", aggregate.notVerified());
        report.put("passRate", passRate);
        report.put("averageLatencyMs", aggregate.averageLatency());
        report.put("totalTokens", aggregate.totalTokens());
        report.put("blockers", blockers);
        report.put("generatedAt", Instant.now());
        jdbcTemplate.update("""
                UPDATE evaluation_runs SET status = ?, gate_decision = ?,
                    total_cases = ?, passed_cases = ?, failed_cases = ?,
                    not_verified_cases = ?, pass_rate = ?, average_latency_ms = ?,
                    total_tokens = ?, report_json = ?::jsonb,
                    finished_at = CASE WHEN ? THEN NULL ELSE now() END
                WHERE id = ? AND status <> 'CANCELED'
                """, status, decision, aggregate.total(), aggregate.passed(), aggregate.failed(),
                aggregate.notVerified(), passRate, aggregate.averageLatency(), aggregate.totalTokens(),
                json(report), pendingStatus != null, runId);
    }

    private RunView loadRun(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, version_id, prompt_version_id, environment, status, gate_decision,
                       idempotency_key, requested_by, total_cases, passed_cases, failed_cases,
                       not_verified_cases, pass_rate, average_latency_ms, total_tokens,
                       report_json::text, error_category, created_at, started_at, finished_at
                FROM evaluation_runs WHERE id = ?
                """, (rs, rowNum) -> {
                    var passRate = rs.getBigDecimal("pass_rate");
                    return new RunView(
                            rs.getObject("id", UUID.class), rs.getLong("version_id"),
                            rs.getObject("prompt_version_id", Long.class), rs.getString("environment"),
                            rs.getString("status"), rs.getString("gate_decision"),
                            rs.getString("idempotency_key"), rs.getString("requested_by"),
                            rs.getInt("total_cases"), rs.getInt("passed_cases"),
                            rs.getInt("failed_cases"), rs.getInt("not_verified_cases"),
                            passRate == null ? null : passRate.doubleValue(),
                            rs.getObject("average_latency_ms", Long.class), rs.getObject("total_tokens", Long.class),
                            readMap(rs.getString("report_json")), rs.getString("error_category"),
                            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                            instant(rs.getTimestamp("finished_at")), caseResults(id));
                }, id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private List<CaseResultView> caseResults(UUID runId) {
        return jdbcTemplate.query("""
                SELECT result.id, evaluation_case.case_key, evaluation_case.title_zh,
                       evaluation_case.title_en, result.status, result.output_hash,
                       result.output_summary, result.score, result.latency_ms,
                       result.input_tokens, result.output_tokens, result.failure_reason,
                       result.assertion_results::text
                FROM evaluation_case_results result
                JOIN evaluation_cases evaluation_case ON evaluation_case.id = result.case_id
                WHERE result.run_id = ? ORDER BY evaluation_case.case_key
                """, (rs, rowNum) -> {
                    var score = rs.getBigDecimal("score");
                    return new CaseResultView(
                            rs.getLong("id"), rs.getString("case_key"), rs.getString("title_zh"),
                            rs.getString("title_en"), rs.getString("status"),
                            rs.getString("output_hash"), rs.getString("output_summary"),
                            score == null ? null : score.doubleValue(),
                            rs.getObject("latency_ms", Long.class),
                            rs.getObject("input_tokens", Integer.class),
                            rs.getObject("output_tokens", Integer.class), rs.getString("failure_reason"),
                            readList(rs.getString("assertion_results")));
                }, runId);
    }

    private DatasetView dataset(long id) {
        return datasets().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private List<VersionView> versions(long datasetId) {
        return jdbcTemplate.query("""
                SELECT id, dataset_id, version_number, status, change_note, content_hash,
                       created_by, submitted_by, reviewed_by, created_at,
                       submitted_at, reviewed_at, published_at
                FROM evaluation_dataset_versions WHERE dataset_id = ?
                ORDER BY version_number DESC
                """, (rs, rowNum) -> new VersionView(
                        rs.getLong("id"), rs.getLong("dataset_id"), rs.getInt("version_number"),
                        rs.getString("status"), rs.getString("change_note"),
                        rs.getString("content_hash"), rs.getString("created_by"),
                        rs.getString("submitted_by"), rs.getString("reviewed_by"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("submitted_at")),
                        instant(rs.getTimestamp("reviewed_at")), instant(rs.getTimestamp("published_at")),
                        cases(rs.getLong("id"))), datasetId);
    }

    private VersionView versionForUpdate(long id) {
        VersionView current = version(id);
        jdbcTemplate.queryForObject("SELECT id FROM evaluation_datasets WHERE id = ? FOR UPDATE", Long.class, current.datasetId());
        return version(id);
    }

    private VersionView version(long id) {
        return jdbcTemplate.query("""
                SELECT id, dataset_id, version_number, status, change_note, content_hash,
                       created_by, submitted_by, reviewed_by, created_at,
                       submitted_at, reviewed_at, published_at
                FROM evaluation_dataset_versions WHERE id = ?
                """, (rs, rowNum) -> new VersionView(
                        rs.getLong("id"), rs.getLong("dataset_id"), rs.getInt("version_number"),
                        rs.getString("status"), rs.getString("change_note"),
                        rs.getString("content_hash"), rs.getString("created_by"),
                        rs.getString("submitted_by"), rs.getString("reviewed_by"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("submitted_at")),
                        instant(rs.getTimestamp("reviewed_at")), instant(rs.getTimestamp("published_at")),
                        cases(id)), id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private List<CaseView> cases(long versionId) {
        return jdbcTemplate.query("""
                SELECT id, version_id, case_key, title_zh, title_en, execution_type,
                       prompt_key, variables_json::text, expected_json::text,
                       forbidden_json::text, critical, enabled, max_latency_ms,
                       max_model_calls, created_at, updated_at
                FROM evaluation_cases WHERE version_id = ? ORDER BY case_key
                """, (rs, rowNum) -> new CaseView(
                        rs.getLong("id"), rs.getLong("version_id"), rs.getString("case_key"),
                        rs.getString("title_zh"), rs.getString("title_en"),
                        ExecutionType.valueOf(rs.getString("execution_type")),
                        rs.getString("prompt_key"), readMap(rs.getString("variables_json")),
                        readMap(rs.getString("expected_json")),
                        stringList(readAny(rs.getString("forbidden_json"))),
                        rs.getBoolean("critical"), rs.getBoolean("enabled"),
                        rs.getObject("max_latency_ms", Long.class),
                        rs.getObject("max_model_calls", Integer.class),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))), versionId);
    }

    private CaseView evaluationCase(long id) {
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT version_id FROM evaluation_cases WHERE id = ?", Long.class, id);
        return cases(versionId).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private PromptGovernanceService.VersionView promptVersion(long id) {
        return promptService.definitions().stream().flatMap(definition -> definition.versions().stream())
                .filter(version -> version.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private String resolvePrompt(Long promptVersionId, String promptKey) {
        if (promptVersionId == null) {
            return promptService.activeTemplate(promptKey)
                    .map(PromptTemplateProvider.Template::content).orElse(null);
        }
        List<String> content = jdbcTemplate.query("""
                SELECT version.content FROM prompt_versions version
                JOIN prompt_definitions definition ON definition.id = version.definition_id
                WHERE version.id = ? AND definition.prompt_key = ?
                """, (rs, rowNum) -> rs.getString(1), promptVersionId, promptKey);
        return content.isEmpty() ? null : content.getFirst();
    }

    private void ensureCatalogSeeded() {
        if (catalogSeeded.get()) return;
        synchronized (catalogSeeded) {
            if (catalogSeeded.get()) return;
            transaction.executeWithoutResult(ignored -> seedFirstForty());
            catalogSeeded.set(true);
        }
    }

    @Transactional
    void seedFirstForty() {
        Long datasetId = jdbcTemplate.queryForObject("""
                INSERT INTO evaluation_datasets (
                    dataset_key, module_key, name_zh, name_en,
                    description_zh, description_en, owner_actor_id
                ) VALUES ('release-first-40', 'CROSS_MODULE', '前 40 个业务基线场景',
                          'First 40 business baseline scenarios',
                          '五个 Copilot 与跨模块 Runtime 的发布基线；由独立 Harness 或 CI 执行并回传。',
                          'Release baseline for five Copilots and cross-module Runtime; executed by an independent harness or CI.',
                          'system')
                ON CONFLICT (dataset_key) DO UPDATE SET dataset_key = EXCLUDED.dataset_key
                RETURNING id
                """, Long.class);
        jdbcTemplate.queryForObject(
                "SELECT id FROM evaluation_datasets WHERE id = ? FOR UPDATE", Long.class, datasetId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_dataset_versions WHERE dataset_id = ?",
                Integer.class, datasetId);
        if (count != null && count > 0) return;
        long versionId = jdbcTemplate.queryForObject("""
                INSERT INTO evaluation_dataset_versions (
                    dataset_id, version_number, status, change_note, content_hash,
                    created_by, submitted_by, reviewed_by, submitted_at, reviewed_at, published_at
                ) VALUES (?, 1, 'PUBLISHED', 'Bundled first-40 catalog',
                          'pending-catalog-hash', 'system', 'system', 'system', now(), now(), now())
                RETURNING id
                """, Long.class, datasetId);
        ClassPathResource resource = new ClassPathResource("evaluation/first-40-scenarios.tsv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().skip(1).filter(line -> !line.isBlank()).forEach(line -> {
                String[] columns = line.split("\\t", -1);
                if (columns.length < 5) return;
                jdbcTemplate.update("""
                        INSERT INTO evaluation_cases (
                            version_id, case_key, title_zh, title_en, execution_type,
                            variables_json, expected_json, forbidden_json,
                            critical, enabled, max_latency_ms, max_model_calls
                        ) VALUES (?, ?, ?, ?, 'EXTERNAL', ?::jsonb, '{}'::jsonb, '[]'::jsonb,
                                  ?, TRUE, 120000, 1)
                        """, versionId, columns[0],
                        columns[0] + " · " + layerLabel(columns[2]),
                        humanize(columns[4]),
                        json(Map.of("testClass", columns[3], "testMethod", columns[4],
                                "module", columns[1], "layer", columns[2])),
                        "SECURITY_GUARDRAIL".equals(columns[2]));
            });
            jdbcTemplate.update("""
                    UPDATE evaluation_dataset_versions SET content_hash = ? WHERE id = ?
                    """, contentHash(cases(versionId)), versionId);
        } catch (IOException ex) {
            throw new IllegalStateException("读取前 40 场景目录失败", ex);
        }
    }

    private void requireDatasetActive(long datasetId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM evaluation_datasets WHERE id = ?", String.class, datasetId);
        if (!"ACTIVE".equals(status)) throw new BusinessException(ErrorCode.STATE_CONFLICT);
    }

    private boolean isWorkerActive(UUID runId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT status = 'RUNNING' FROM evaluation_runs WHERE id = ?", Boolean.class, runId));
    }

    private void touchWorkerHeartbeat(UUID runId) {
        jdbcTemplate.update("""
                UPDATE evaluation_runs SET heartbeat_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, runId);
    }

    private static void validateCase(CaseCommand command) {
        if (command.executionType() == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (command.executionType() == ExecutionType.PROMPT
                && (command.promptKey() == null || command.promptKey().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Prompt 评测用例必须选择 Prompt。");
        }
        if (command.maxLatencyMs() != null && command.maxLatencyMs() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (command.maxModelCalls() != null && command.maxModelCalls() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static void requireDraftOwner(VersionView version, CurrentActor actor) {
        if (!"DRAFT".equals(version.status())
                || (!actor.hasRole(BusinessRole.ADMIN) && !actor.actorId().equals(version.createdBy()))) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
    }

    private CurrentActor requireAuthenticated() {
        CurrentActor actor = actorProvider.currentActor();
        if (actor == null || !actor.authenticated()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return actor;
    }

    private CurrentActor requireOperatorOrAdmin() {
        CurrentActor actor = requireAuthenticated();
        if (!actor.hasRole(BusinessRole.ADMIN) && !actor.hasRole(BusinessRole.OPERATOR)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return actor;
    }

    private CurrentActor requireReviewerOrAdmin() {
        CurrentActor actor = requireAuthenticated();
        if (!actor.hasRole(BusinessRole.ADMIN) && !actor.hasRole(BusinessRole.REVIEWER)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return actor;
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = requireAuthenticated();
        if (!actor.hasRole(BusinessRole.ADMIN)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return actor;
    }

    private String contentHash(List<CaseView> cases) {
        return sha256(json(cases.stream().map(item -> Map.of(
                "caseKey", item.caseKey(), "executionType", item.executionType().name(),
                "promptKey", item.promptKey() == null ? "" : item.promptKey(),
                "variables", item.variables(), "expected", item.expected(),
                "forbidden", item.forbidden(), "critical", item.critical(),
                "enabled", item.enabled())).toList()));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException ex) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
    }

    private Object readAny(String value) {
        if (value == null) return null;
        try { return objectMapper.readValue(value, Object.class); }
        catch (JacksonException ex) { return null; }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null) return Map.of();
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException ex) { return Map.of(); }
    }

    private List<Map<String, Object>> readList(String value) {
        if (value == null) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException ex) { return List.of(); }
    }

    private static Map<String, String> stringMap(Map<String, Object> value) {
        Map<String, String> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(key, item == null ? "" : String.valueOf(item)));
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null).map(String::valueOf).toList();
    }

    private static String substitute(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> assertion(String kind, String expected, boolean passed) {
        return Map.of("kind", kind, "expected", expected, "passed", passed);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { return null; }
    }

    private static String normalizeModule(String value) {
        try { return ModuleKey.valueOf(normalizeRequired(value).toUpperCase(Locale.ROOT)).name(); }
        catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
    }

    private static String normalizeKey(String value) {
        String normalized = normalizeRequired(value).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{1,99}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    private static String normalizeRequired(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return value.strip();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String humanize(String value) {
        return value.replaceAll("([a-z])([A-Z0-9])", "$1 $2")
                .replace('_', ' ').strip();
    }

    private static String layerLabel(String layer) {
        return switch (layer) {
            case "SECURITY_GUARDRAIL" -> "安全边界";
            case "PRODUCTION_SERVICE" -> "业务服务";
            case "DATABASE" -> "数据库闭环";
            case "RUNTIME" -> "运行时恢复";
            default -> layer;
        };
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public enum ModuleKey { DATA, KNOWLEDGE, SUPPORT, REPORT, HR, CROSS_MODULE }
    public enum ExecutionType { PROMPT, EXTERNAL }
    public enum Environment { LOCAL, MODEL, VENDOR, PRE_PRODUCTION }
    public enum ResultStatus { PASSED, FAILED, NOT_VERIFIED }

    public record DatasetCommand(String datasetKey, String moduleKey, String nameZh, String nameEn,
                                 String descriptionZh, String descriptionEn) { }
    public record DatasetView(long id, String datasetKey, String moduleKey, String nameZh,
                              String nameEn, String descriptionZh, String descriptionEn,
                              String status, String ownerActorId, Instant createdAt,
                              Instant updatedAt, List<VersionView> versions) { }
    public record VersionView(long id, long datasetId, int versionNumber, String status,
                              String changeNote, String contentHash, String createdBy,
                              String submittedBy, String reviewedBy, Instant createdAt,
                              Instant submittedAt, Instant reviewedAt, Instant publishedAt,
                              List<CaseView> cases) { }
    public record CaseCommand(String caseKey, String titleZh, String titleEn,
                              ExecutionType executionType, String promptKey,
                              Map<String, Object> variables, Map<String, Object> expected,
                              List<String> forbidden, boolean critical, boolean enabled,
                              Long maxLatencyMs, Integer maxModelCalls) {
        public CaseCommand {
            variables = variables == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(variables));
            expected = expected == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(expected));
            forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        }
    }
    public record CaseView(long id, long versionId, String caseKey, String titleZh,
                           String titleEn, ExecutionType executionType, String promptKey,
                           Map<String, Object> variables, Map<String, Object> expected,
                           List<String> forbidden, boolean critical, boolean enabled,
                           Long maxLatencyMs, Integer maxModelCalls,
                           Instant createdAt, Instant updatedAt) { }
    public record GatePolicyCommand(String moduleKey, double minimumPassRate,
                                    boolean requireCriticalPass, Long maximumAverageLatency,
                                    Long maximumTotalTokens) { }
    public record GatePolicy(String moduleKey, double minimumPassRate,
                             boolean requireCriticalPass, Long maximumAverageLatency,
                             Long maximumTotalTokens, String updatedBy, Instant updatedAt) { }
    public record RunCommand(long versionId, Long promptVersionId,
                             Environment environment, String idempotencyKey) { }
    public record RunView(UUID id, long versionId, Long promptVersionId, String environment,
                          String status, String gateDecision, String idempotencyKey,
                          String requestedBy, int totalCases, int passedCases, int failedCases,
                          int notVerifiedCases, Double passRate, Long averageLatencyMs,
                          Long totalTokens, Map<String, Object> report, String errorCategory,
                          Instant createdAt, Instant startedAt, Instant finishedAt,
                          List<CaseResultView> results) { }
    public record CaseResultView(long id, String caseKey, String titleZh, String titleEn,
                                 String status, String outputHash, String outputSummary,
                                 Double score, Long latencyMs, Integer inputTokens,
                                 Integer outputTokens, String failureReason,
                                 List<Map<String, Object>> assertions) {
        public String title() { return titleZh != null ? titleZh : titleEn; }
    }
    public record ExternalResultCommand(String caseKey, ResultStatus status, String outputHash,
                                        String summary, Long latencyMs, Integer inputTokens,
                                        Integer outputTokens, String failureReason,
                                        String evidenceReference) { }

    private record CaseExecution(ResultStatus status, String outputHash, String outputSummary,
                                 double score, Long latencyMs, Integer inputTokens,
                                 Integer outputTokens, String failureReason,
                                 List<Map<String, Object>> assertions) {
        static CaseExecution notVerified(String reason) {
            return new CaseExecution(ResultStatus.NOT_VERIFIED, null, null, 0,
                    null, null, null, reason, List.of());
        }
        static CaseExecution failed(String reason) {
            return new CaseExecution(ResultStatus.FAILED, null, null, 0,
                    null, null, null, reason, List.of());
        }
    }
    private record Aggregate(int total, int passed, int failed, int notVerified,
                             Long averageLatency, Long totalTokens, int criticalFailed, int unknownLatency) { }
}
