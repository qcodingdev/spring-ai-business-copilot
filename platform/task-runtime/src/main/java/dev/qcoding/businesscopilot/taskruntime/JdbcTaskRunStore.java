package dev.qcoding.businesscopilot.taskruntime;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed store for task runs, steps and attempts.
 *
 * <p>基于平台库 JdbcTemplate 的运行记录存储。Schema 由应用 Flyway 迁移
 * {@code V34__task_runtime.sql} 提供。预算与上下文清单使用离散列存储，
 * 证据引用使用字符串数组；所有条件更新（领取、恢复、消费）使用状态条件语句，
 * 避免两个执行者同时操作同一运行时互相覆盖。</p>
 */
public class JdbcTaskRunStore implements TaskRunStore {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public JdbcTaskRunStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper());
    }

    public JdbcTaskRunStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(
                        java.util.Objects.requireNonNull(jdbcTemplate.getDataSource())));
    }

    private final RowMapper<TaskRun> runMapper = (rs, rowNum) -> new TaskRun(
            rs.getString("run_id"),
            rs.getString("module_name"),
            rs.getString("ref_type"),
            rs.getString("ref_id"),
            rs.getString("owner_actor_id"),
            TaskRunStatus.valueOf(rs.getString("status")),
            new TaskRunBudget(
                    (Integer) rs.getObject("max_model_calls"),
                    (Integer) rs.getObject("max_tool_calls"),
                    (Integer) rs.getObject("max_tokens"),
                    rs.getObject("max_duration_seconds") != null
                            ? Duration.ofSeconds(rs.getLong("max_duration_seconds")) : null),
            new ContextManifest(
                    readStringList(rs.getString("context_scope_refs")),
                    rs.getString("context_auth_source"),
                    rs.getObject("context_retention_seconds") != null
                            ? Duration.ofSeconds(rs.getLong("context_retention_seconds")) : null,
                    toInstant(rs.getTimestamp("context_expires_at"))),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("ended_at")),
            rs.getString("failure_category") != null
                    ? FailureCategory.valueOf(rs.getString("failure_category")) : null,
            rs.getString("stop_reason"),
            rs.getString("confirmed_by_actor_id"));

    private static final RowMapper<TaskStep> STEP_MAPPER = (rs, rowNum) -> new TaskStep(
            rs.getString("step_id"),
            rs.getString("run_id"),
            rs.getString("step_name"),
            TaskStep.TaskStepStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt_count"),
            rs.getString("failure_category") != null
                    ? FailureCategory.valueOf(rs.getString("failure_category")) : null,
            List.of(), // 见 findSteps：证据引用在查询时拼装
            rs.getString("summary"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("ended_at")));

    private static final RowMapper<TaskAttempt> ATTEMPT_MAPPER = (rs, rowNum) -> new TaskAttempt(
            rs.getString("attempt_id"),
            rs.getString("run_id"),
            rs.getString("step_id"),
            TaskAttempt.Kind.valueOf(rs.getString("kind")),
            rs.getString("operation"),
            rs.getString("provider_name"),
            rs.getString("model_name"),
            (Integer) rs.getObject("input_tokens"),
            (Integer) rs.getObject("output_tokens"),
            rs.getLong("latency_ms"),
            TaskAttempt.Outcome.valueOf(rs.getString("outcome")),
            rs.getString("failure_category") != null
                    ? FailureCategory.valueOf(rs.getString("failure_category")) : null,
            toInstant(rs.getTimestamp("occurred_at")));

    @Override
    public <T> T withRunLock(String runId, java.util.function.Supplier<T> action) {
        return transaction.execute(status -> {
            jdbcTemplate.query("SELECT run_id FROM task_runs WHERE run_id = ? FOR UPDATE",
                    (rs, row) -> rs.getString(1), runId);
            return action.get();
        });
    }

    @Override
    public void saveRun(TaskRun run) {
        jdbcTemplate.update("""
                INSERT INTO task_runs (run_id, module_name, ref_type, ref_id, owner_actor_id, status,
                                       max_model_calls, max_tool_calls, max_tokens,
                                       max_duration_seconds, context_auth_source,
                                       context_retention_seconds, context_expires_at,
                                       started_at, ended_at, failure_category, stop_reason,
                                       confirmed_by_actor_id, context_scope_refs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                run.runId(), run.module(), run.refType(), run.refId(), run.ownerActorId(),
                run.status().name(),
                budgetValue(run.budget(), run.budget() != null ? run.budget().maxModelCalls() : null),
                budgetValue(run.budget(), run.budget() != null ? run.budget().maxToolCalls() : null),
                budgetValue(run.budget(), run.budget() != null ? run.budget().maxTokens() : null),
                run.budget() != null && run.budget().maxDuration() != null
                        ? run.budget().maxDuration().toSeconds() : null,
                run.contextManifest() != null ? run.contextManifest().authorizationSource() : null,
                run.contextManifest() != null && run.contextManifest().retention() != null
                        ? run.contextManifest().retention().toSeconds() : null,
                run.contextManifest() != null && run.contextManifest().expiresAt() != null
                        ? Timestamp.from(run.contextManifest().expiresAt()) : null,
                timestamp(run.startedAt()), timestamp(run.endedAt()),
                run.failureCategory() != null ? run.failureCategory().name() : null,
                run.stopReason(), run.confirmedByActorId(),
                writeStringList(run.contextManifest() != null ? run.contextManifest().dataScopeRefs() : List.of()));
    }

    @Override
    public boolean updateRunIfStatus(String runId, TaskRunStatus expectedStatus, TaskRun updated) {
        return jdbcTemplate.update("""
                UPDATE task_runs
                SET status = ?, ended_at = ?, failure_category = ?, stop_reason = ?,
                    confirmed_by_actor_id = ?
                WHERE run_id = ? AND status = ?
                """,
                updated.status().name(), timestamp(updated.endedAt()),
                updated.failureCategory() != null ? updated.failureCategory().name() : null,
                updated.stopReason(), updated.confirmedByActorId(),
                runId, expectedStatus.name()) > 0;
    }

    @Override
    public void updateRun(TaskRun run) {
        jdbcTemplate.update("""
                UPDATE task_runs
                SET status = ?, ended_at = ?, failure_category = ?, stop_reason = ?,
                    confirmed_by_actor_id = ?
                WHERE run_id = ?
                """,
                run.status().name(), timestamp(run.endedAt()),
                run.failureCategory() != null ? run.failureCategory().name() : null,
                run.stopReason(), run.confirmedByActorId(), run.runId());
    }

    @Override
    public Optional<TaskRun> findRun(String runId) {
        List<TaskRun> runs = jdbcTemplate.query(
                "SELECT * FROM task_runs WHERE run_id = ?", runMapper, runId);
        return runs.stream().findFirst();
    }

    @Override
    public List<TaskRun> findByStatus(TaskRunStatus status) {
        return jdbcTemplate.query(
                "SELECT * FROM task_runs WHERE status = ? ORDER BY started_at", runMapper, status.name());
    }

    @Override
    public List<String> findRunIdsWithStartedAttemptsBefore(Instant cutoff) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT run.run_id
                FROM task_runs run
                JOIN task_attempts attempt ON attempt.run_id = run.run_id
                WHERE run.status = 'RUNNING'
                  AND attempt.outcome = 'STARTED'
                  AND attempt.occurred_at <= ?
                ORDER BY run.run_id
                """, String.class, Timestamp.from(cutoff));
    }

    @Override
    public void saveStep(TaskStep step) {
        jdbcTemplate.update("""
                INSERT INTO task_steps (step_id, run_id, step_name, status, attempt_count,
                                        failure_category, evidence_refs, summary, started_at, ended_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                step.stepId(), step.runId(), step.name(), step.status().name(),
                step.attemptCount(),
                step.failureCategory() != null ? step.failureCategory().name() : null,
                writeStringList(step.evidenceRefs()), step.summary(),
                timestamp(step.startedAt()), timestamp(step.endedAt()));
    }

    @Override
    public void updateStep(TaskStep step) {
        jdbcTemplate.update("""
                UPDATE task_steps
                SET status = ?, attempt_count = ?, failure_category = ?, evidence_refs = ?::jsonb,
                    summary = ?, ended_at = ?
                WHERE step_id = ?
                """,
                step.status().name(), step.attemptCount(),
                step.failureCategory() != null ? step.failureCategory().name() : null,
                writeStringList(step.evidenceRefs()), step.summary(), timestamp(step.endedAt()),
                step.stepId());
    }

    @Override
    public Optional<TaskStep> findStep(String stepId) {
        List<TaskStep> steps = jdbcTemplate.query(
                "SELECT * FROM task_steps WHERE step_id = ?",
                (rs, rowNum) -> withEvidenceRefs(STEP_MAPPER.mapRow(rs, rowNum), rs), stepId);
        return steps.stream().findFirst();
    }

    @Override
    public List<TaskStep> findSteps(String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM task_steps WHERE run_id = ? ORDER BY started_at, step_id",
                (rs, rowNum) -> withEvidenceRefs(STEP_MAPPER.mapRow(rs, rowNum), rs), runId);
    }

    /** 行映射后补充 jsonb 数组形式的证据引用。 */
    private TaskStep withEvidenceRefs(TaskStep step, java.sql.ResultSet rs) throws java.sql.SQLException {
        String json = rs.getString("evidence_refs");
        if (json == null || json.isBlank()) {
            return step;
        }
        List<String> refs = new java.util.ArrayList<>();
        objectMapper.readTree(json).forEach(node -> refs.add(node.asString()));
        return new TaskStep(step.stepId(), step.runId(), step.name(), step.status(),
                step.attemptCount(), step.failureCategory(), List.copyOf(refs), step.summary(),
                step.startedAt(), step.endedAt());
    }

    @Override
    public void saveAttempt(TaskAttempt attempt) {
        jdbcTemplate.update("""
                INSERT INTO task_attempts (attempt_id, run_id, step_id, kind, operation,
                                           provider_name, model_name, input_tokens, output_tokens,
                                           latency_ms, outcome, failure_category, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                attempt.attemptId(), attempt.runId(), attempt.stepId(), attempt.kind().name(),
                attempt.operation(), attempt.provider(), attempt.model(),
                attempt.inputTokens(), attempt.outputTokens(),
                attempt.latencyMs(), attempt.outcome().name(),
                attempt.failureCategory() != null ? attempt.failureCategory().name() : null,
                timestamp(attempt.occurredAt()));
    }

    @Override
    public void updateAttempt(TaskAttempt attempt) {
        jdbcTemplate.update("""
                UPDATE task_attempts
                SET provider_name = ?, model_name = ?, input_tokens = ?, output_tokens = ?,
                    latency_ms = ?, outcome = ?, failure_category = ?
                WHERE attempt_id = ? AND outcome = 'STARTED'
                """, attempt.provider(), attempt.model(), attempt.inputTokens(),
                attempt.outputTokens(), attempt.latencyMs(), attempt.outcome().name(),
                attempt.failureCategory() != null ? attempt.failureCategory().name() : null,
                attempt.attemptId());
    }

    @Override
    public List<TaskAttempt> findAttempts(String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM task_attempts WHERE run_id = ? ORDER BY occurred_at, attempt_id",
                ATTEMPT_MAPPER, runId);
    }

    private Integer budgetValue(TaskRunBudget budget, Integer value) {
        return budget == null ? null : value;
    }

    private String writeStringList(List<String> values) {
        return values == null || values.isEmpty() ? "[]" : objectMapper.writeValueAsString(values);
    }

    private List<String> readStringList(String json) {
        if (json == null) return List.of();
        List<String> values = new java.util.ArrayList<>();
        objectMapper.readTree(json).forEach(node -> values.add(node.asString()));
        return List.copyOf(values);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
    @Override
    public List<TaskRun> findByOwner(String ownerActorId, int limit) {
        return jdbcTemplate.query("""
                SELECT run_id, module_name, ref_type, ref_id, owner_actor_id, status,
                       max_model_calls, max_tool_calls, max_tokens, max_duration_seconds,
                       context_auth_source, context_retention_seconds, context_expires_at,
                       context_scope_refs, started_at, ended_at, failure_category,
                       stop_reason, confirmed_by_actor_id
                FROM task_runs
                WHERE owner_actor_id = ?
                ORDER BY started_at DESC
                LIMIT ?
                """, runMapper, ownerActorId, limit);
    }

    @Override
    public int purgeExpiredRuns(java.time.Instant now) {
        // 上下文到期即停止保留；包括遗留在等待确认或结果未知状态的运行（RUN-07）。
        return jdbcTemplate.update("""
                DELETE FROM task_runs
                WHERE context_expires_at IS NOT NULL
                  AND context_expires_at < ?
                """, Timestamp.from(now));
    }
}
