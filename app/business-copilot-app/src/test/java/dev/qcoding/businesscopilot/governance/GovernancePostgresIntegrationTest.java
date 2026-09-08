package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static dev.qcoding.businesscopilot.governance.EvaluationManagementService.*;

/** Governance gates are tested against PostgreSQL; only the model/provider is scripted. */
@Testcontainers
class GovernancePostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static final CurrentActorProvider ADMIN = () -> new CurrentActor("governance-test", Set.of(BusinessRole.ADMIN));

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearContext() {
        BusinessRequestContextHolder.clear();
    }

    @Test
    void rolloutCacheKeepsBothCohortsAndStableSelectionWithinOneRequest() {
        var prompts = new PromptGovernanceService(jdbc, ADMIN);
        var definition = prompts.definitions().getFirst();
        long oldVersion = definition.activeVersionId();
        var candidate = prompts.createVersion(definition.id(), "灰度候选模板", "灰度缓存回归");
        jdbc.update("UPDATE prompt_definitions SET previous_version_id = ?, active_version_id = ?, rollout_percent = 50 WHERE id = ?",
                oldVersion, candidate.id(), definition.id());
        try {
            Set<String> selected = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                BusinessRequestContextHolder.set(new BusinessRequestContext("request-" + i,
                        "actor-" + i, Set.of("OPERATOR"), "zh-CN"));
                var first = prompts.activeTemplate(definition.promptKey()).orElseThrow();
                assertThat(prompts.activeTemplate(definition.promptKey())).contains(first);
                selected.add(first.contentHash());
            }
            assertThat(selected).as("a 50%% rollout must not reuse the first request's selected variant").hasSize(2);
        } finally {
            jdbc.update("UPDATE prompt_definitions SET active_version_id = ?, previous_version_id = NULL, rollout_percent = 100 WHERE id = ?",
                    oldVersion, definition.id());
        }
    }

    @Test
    void partialExternalResultsCannotPassBeforeTheRemainingCasesArrive() {
        var service = service(task -> { }); // A queued worker has not produced placeholder results yet.
        long version = published(service, external("first", true), external("missing", true));
        var run = service.startRun(new RunCommand(version, null, Environment.LOCAL, UUID.randomUUID().toString()));
        var partial = service.recordExternalResults(run.id(), List.of(passed("first")));
        assertThat(partial.gateDecision()).isEqualTo("NOT_VERIFIED");
        assertThat(partial.totalCases()).isEqualTo(2);
        assertThat(partial.notVerifiedCases()).isEqualTo(1);
        var complete = service.recordExternalResults(run.id(), List.of(passed("missing")));
        assertThat(complete.gateDecision()).isEqualTo("ALLOW_RELEASE");
    }

    @Test
    void disabledExternalCasesCannotInflateThePassRate() {
        var service = service(Runnable::run);
        long version = published(service, external("enabled", true), external("disabled", false));
        var run = service.startRun(new RunCommand(version, null, Environment.LOCAL, UUID.randomUUID().toString()));
        assertThatThrownBy(() -> service.recordExternalResults(run.id(), List.of(passed("disabled"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void missingProviderUsageCannotSatisfyATokenGate() {
        var prompts = new PromptGovernanceService(jdbc, ADMIN);
        String key = prompts.definitions().getFirst().promptKey();
        AiChatService ai = mock(AiChatService.class);
        when(ai.isModelEnabled()).thenReturn(true);
        when(ai.generateTextWithMetadata(eq("evaluation.prompt"), anyString(), org.mockito.ArgumentMatchers.any(dev.qcoding.businesscopilot.aicore.AiAttemptObserver.class)))
                .thenReturn(new AiInvocationResult<>("safe output",
                        new AiInvocationMetadata("fixture", "fixture", "request", null, null, "stop", 10)));
        var service = new EvaluationManagementService(jdbc, new ObjectMapper(), ADMIN, ai, prompts, Runnable::run, new JdbcTransactionManager(jdbc.getDataSource()));
        long version = published(service, new CaseCommand("unknown-usage", "未知用量", "Unknown usage",
                ExecutionType.PROMPT, key, Map.of(), Map.of("contains", List.of("safe")), List.of(), true, true, null, 1));
        var run = service.startRun(new RunCommand(version, null, Environment.MODEL, UUID.randomUUID().toString()));
        assertThat(run.gateDecision()).isEqualTo("NOT_VERIFIED");
        assertThat(run.results().getFirst().inputTokens()).isNull();
        assertThat(run.results().getFirst().outputTokens()).isNull();
    }

    @Test
    void archivedDatasetsCannotDispatchNewRuns() {
        var service = service(Runnable::run);
        long version = published(service, external("case-one", true));
        long dataset = jdbc.queryForObject("SELECT dataset_id FROM evaluation_dataset_versions WHERE id = ?", Long.class, version);
        service.archiveDataset(dataset);
        assertThatThrownBy(() -> service.startRun(new RunCommand(version, null, Environment.LOCAL, UUID.randomUUID().toString())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectedDispatchDoesNotLeaveAnUnrecoverableQueuedRun() {
        var service = service(task -> { throw new RejectedExecutionException("queue is full"); });
        long version = published(service, external("case-one", true));
        String key = UUID.randomUUID().toString();
        try { service.startRun(new RunCommand(version, null, Environment.LOCAL, key)); }
        catch (RuntimeException ignored) { /* Either an API error or a failed run is acceptable. */ }
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_runs WHERE idempotency_key = ?", String.class, key))
                .isEqualTo("FAILED");
    }

    @Test
    void staleQueuedAndRunningEvaluationsBecomeExplicitlyUnverifiedAfterRestart() {
        var service = service(task -> { });
        long version = published(service, external("case-one", true));
        var queued = service.startRun(new RunCommand(
                version, null, Environment.LOCAL, UUID.randomUUID().toString()));
        var running = service.startRun(new RunCommand(
                version, null, Environment.LOCAL, UUID.randomUUID().toString()));
        var fresh = service.startRun(new RunCommand(
                version, null, Environment.LOCAL, UUID.randomUUID().toString()));
        var activeHeartbeat = service.startRun(new RunCommand(
                version, null, Environment.LOCAL, UUID.randomUUID().toString()));
        jdbc.update("UPDATE evaluation_runs SET created_at = now() - interval '1 hour' WHERE id = ?", queued.id());
        jdbc.update("""
                UPDATE evaluation_runs
                SET status = 'RUNNING', started_at = now() - interval '1 hour',
                    heartbeat_at = now() - interval '1 hour'
                WHERE id = ?
                """, running.id());
        jdbc.update("""
                UPDATE evaluation_runs
                SET status = 'RUNNING', started_at = now() - interval '1 hour', heartbeat_at = now()
                WHERE id = ?
                """, activeHeartbeat.id());

        assertThat(service.reconcileInterruptedRuns(Duration.ofMinutes(15))).isEqualTo(2);
        assertThat(service.run(queued.id())).satisfies(run -> {
            assertThat(run.status()).isEqualTo("FAILED");
            assertThat(run.gateDecision()).isEqualTo("NOT_VERIFIED");
            assertThat(run.errorCategory()).isEqualTo("PROCESS_INTERRUPTED");
            assertThat(run.finishedAt()).isNotNull();
        });
        assertThat(service.run(running.id()).status()).isEqualTo("FAILED");
        assertThat(service.run(fresh.id()).status()).isEqualTo("QUEUED");
        assertThat(service.run(activeHeartbeat.id()).status()).isEqualTo("RUNNING");
    }

    @Test
    void canceledRunsRejectExternalResultChanges() {
        var service = service(task -> { });
        long version = published(service, external("case-one", true));
        var run = service.startRun(new RunCommand(version, null, Environment.LOCAL, UUID.randomUUID().toString()));
        service.cancelRun(run.id());
        assertThatThrownBy(() -> service.recordExternalResults(run.id(), List.of(passed("case-one"))))
                .isInstanceOf(BusinessException.class);
        assertThat(service.run(run.id()).results()).isEmpty();
    }

    private EvaluationManagementService service(TaskExecutor executor) {
        return new EvaluationManagementService(jdbc, new ObjectMapper(), ADMIN, mock(AiChatService.class),
                new PromptGovernanceService(jdbc, ADMIN), executor, new JdbcTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void earlyExternalResultsKeepThePromptWorkerQueuedAndAreNotOverwritten() {
        var worker = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var prompts = new PromptGovernanceService(jdbc, ADMIN);
        String promptKey = prompts.definitions().getFirst().promptKey();
        AiChatService ai = mock(AiChatService.class);
        when(ai.isModelEnabled()).thenReturn(true);
        when(ai.generateTextWithMetadata(eq("evaluation.prompt"), anyString(), org.mockito.ArgumentMatchers.any(dev.qcoding.businesscopilot.aicore.AiAttemptObserver.class)))
                .thenReturn(new AiInvocationResult<>("safe output",
                        new AiInvocationMetadata("fixture", "fixture", "request", 10, 5, "stop", 10)));
        var service = new EvaluationManagementService(jdbc, new ObjectMapper(), ADMIN, ai, prompts,
                worker::set, new JdbcTransactionManager(jdbc.getDataSource()));
        long version = published(service, external("a-external", true),
                new CaseCommand("b-prompt", "模型", "Prompt", ExecutionType.PROMPT, promptKey,
                        Map.of(), Map.of("contains", List.of("safe")), List.of(), true, true, null, 1));
        var run = service.startRun(new RunCommand(version, null, Environment.MODEL, UUID.randomUUID().toString()));
        var early = service.recordExternalResults(run.id(), List.of(passed("a-external")));
        assertThat(early.status()).isEqualTo("QUEUED");
        assertThat(early.gateDecision()).isEqualTo("NOT_VERIFIED");
        assertThat(early.finishedAt()).isNull();
        worker.get().run();
        var complete = service.run(run.id());
        assertThat(complete.gateDecision()).isEqualTo("ALLOW_RELEASE");
        assertThat(complete.results()).hasSize(2).allSatisfy(result -> assertThat(result.status()).isEqualTo("PASSED"));
    }

    @Test
    void idempotencyKeyIsNormalizedAndCannotBeReusedForADifferentRequest() {
        var service = service(task -> { });
        long version = published(service, external("one", true));
        String key = UUID.randomUUID().toString();
        var first = service.startRun(new RunCommand(version, null, Environment.LOCAL, " " + key + " "));
        assertThat(service.startRun(new RunCommand(version, null, Environment.LOCAL, " " + key + " ")).id())
                .isEqualTo(first.id());
        assertThatThrownBy(() -> service.startRun(new RunCommand(version, null, Environment.MODEL, key)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void modelAttemptLimitStopsARetryBeforeProviderDispatch() {
        var prompts = new PromptGovernanceService(jdbc, ADMIN);
        String promptKey = prompts.definitions().getFirst().promptKey();
        AiChatService ai = mock(AiChatService.class);
        var dispatched = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.isModelEnabled()).thenReturn(true);
        when(ai.generateTextWithMetadata(eq("evaluation.prompt"), anyString(), org.mockito.ArgumentMatchers.any(dev.qcoding.businesscopilot.aicore.AiAttemptObserver.class)))
                .thenAnswer(call -> {
                    dev.qcoding.businesscopilot.aicore.AiAttemptObserver observer = call.getArgument(2);
                    for (int attempt = 0; attempt < 2; attempt++) {
                        observer.beforeAttempt("evaluation.prompt", "fixture", "fixture", 10);
                        dispatched.incrementAndGet();
                    }
                    throw new AssertionError("The second provider attempt must not be authorized");
                });
        var service = new EvaluationManagementService(jdbc, new ObjectMapper(), ADMIN, ai, prompts,
                Runnable::run, new JdbcTransactionManager(jdbc.getDataSource()));
        long version = published(service, new CaseCommand("limited", "有界调用", "Bounded calls", ExecutionType.PROMPT,
                promptKey, Map.of(), Map.of("contains", List.of("safe")), List.of(), true, true, null, 1));
        var run = service.startRun(new RunCommand(version, null, Environment.MODEL, UUID.randomUUID().toString()));
        assertThat(dispatched.get()).isEqualTo(1);
        assertThat(run.results().getFirst().status()).isEqualTo("FAILED");
        assertThat(run.results().getFirst().failureReason()).contains("调用次数");
        assertThat(run.gateDecision()).isNotEqualTo("ALLOW_RELEASE");
    }

    @Test
    void externalOnlyDatasetCannotCertifyAPromptVersion() {
        var service = service(task -> { });
        long version = published(service, external("one", true));
        long promptVersion = new PromptGovernanceService(jdbc, ADMIN).definitions().getFirst().activeVersionId();
        assertThatThrownBy(() -> service.startRun(new RunCommand(version, promptVersion, Environment.MODEL, UUID.randomUUID().toString())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void concurrentDraftEditCannotChangeAJustSubmittedVersion() throws Exception {
        var service = service(task -> { });
        var dataset = service.createDataset(new DatasetCommand("locking-" + UUID.randomUUID(), "DATA", "锁测试", "Lock regression", null, null));
        long version = dataset.versions().getFirst().id();
        var original = service.saveCase(version, null, external("original", true));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var started = new java.util.concurrent.CountDownLatch(1);
            var pending = transaction.execute(status -> {
                jdbc.queryForObject("SELECT id FROM evaluation_datasets WHERE id = ? FOR UPDATE", Long.class, dataset.id());
                var future = executor.submit(() -> transaction.execute(ignored -> {
                    started.countDown();
                    return service.setCaseEnabled(version, original.id(), false);
                }));
                try { assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                service.submitVersion(version);
                return future;
            });
            assertThatThrownBy(() -> pending.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("SELECT enabled FROM evaluation_cases WHERE id = ?", Boolean.class, original.id())).isTrue();
        }
    }

    private long published(EvaluationManagementService service, CaseCommand... cases) {
        var dataset = service.createDataset(new DatasetCommand("regression-" + UUID.randomUUID(), "DATA",
                "治理回归", "Governance regression", null, null));
        long version = dataset.versions().getFirst().id();
        for (CaseCommand item : cases) service.saveCase(version, null, item);
        service.submitVersion(version);
        service.reviewVersion(version, true, "Synthetic regression fixture approved");
        service.publishVersion(version);
        return version;
    }

    private CaseCommand external(String key, boolean enabled) {
        return new CaseCommand(key, key, key, ExecutionType.EXTERNAL, null, Map.of(), Map.of(), List.of(), true, enabled, null, 1);
    }

    private ExternalResultCommand passed(String key) {
        return new ExternalResultCommand(key, ResultStatus.PASSED, "fixture-hash", "Passed", 10L, 0, 0, null, "fixture://" + key);
    }
}
