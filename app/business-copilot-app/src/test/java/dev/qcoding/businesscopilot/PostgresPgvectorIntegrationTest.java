package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.audit.AuditEvent;
import dev.qcoding.businesscopilot.audit.AuditEventType;
import dev.qcoding.businesscopilot.audit.AuditStatus;
import dev.qcoding.businesscopilot.acceptance.AcceptanceEvidence;
import dev.qcoding.businesscopilot.acceptance.AcceptanceEvidenceService;
import dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy;
import dev.qcoding.businesscopilot.taskruntime.*;
import dev.qcoding.businesscopilot.audit.JdbcQueryAuditRepository;
import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataQueryResultService;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationResponse;
import dev.qcoding.businesscopilot.datacopilot.query.QueryColumn;
import dev.qcoding.businesscopilot.datacopilot.confirmation.JdbcSqlCandidateStore;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidate;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateStore;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataEnterpriseProperties;
import dev.qcoding.businesscopilot.datacopilot.enterprise.MetricDictionaryService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.SqlCandidateRevisionService;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationResponse;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;
import dev.qcoding.businesscopilot.datacopilot.query.QueryRow;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService;
import dev.qcoding.businesscopilot.reportcopilot.draft.JdbcReportDraftRepository;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService;
import dev.qcoding.businesscopilot.reportcopilot.enterprise.ReportEnterpriseService;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportDraftResponse;
import dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportCitation;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationOutputValidator;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportOutputSanitizer;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportPromptContextFactory;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestValidator;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceMapper;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceNormalizer;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.ConfirmationTokenService;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ExternalConnectionSecurityProperties;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.JdbcKnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.EmbeddingIndexResult;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.JdbcKnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeChunkEmbedding;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.PreparedKnowledgeIndex;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.JdbcKnowledgeIndexJobRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexLifecycleService;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJobStatus;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeQueryTerms;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeRetrievalService;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceAdapter;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceConnection;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceProvider;
import dev.qcoding.businesscopilot.knowledgecopilot.source.KnowledgeSourceSyncService;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.JdbcKnowledgeFeedbackRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackRating;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackReason;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityReviewDecision;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeEvidenceAssessment;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeAnswerAssessment;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeRemediationAction;
import dev.qcoding.businesscopilot.readiness.EnterpriseReadinessProperties;
import dev.qcoding.businesscopilot.readiness.JdbcEnterpriseReadinessProbeRepository;
import dev.qcoding.businesscopilot.datacopilot.schema.DataCopilotSchemaProperties;
import dev.qcoding.businesscopilot.datacopilot.schema.JdbcSchemaMetadataRepository;
import dev.qcoding.businesscopilot.demo.DemoModule;
import dev.qcoding.businesscopilot.demo.DemoOperation;
import dev.qcoding.businesscopilot.demo.DemoDataInitializationService;
import dev.qcoding.businesscopilot.demo.DemoDataJobRepository;
import dev.qcoding.businesscopilot.demo.DemoScenario;
import dev.qcoding.businesscopilot.demo.DemoScenarioRepository;
import dev.qcoding.businesscopilot.demo.PublicDemoInputGuard;
import dev.qcoding.businesscopilot.demo.PublicDemoProperties;
import dev.qcoding.businesscopilot.demo.PublicDemoQuotaService;
import dev.qcoding.businesscopilot.supportcopilot.queue.SupportQueueService;
import dev.qcoding.businesscopilot.supportcopilot.classification.SupportRiskLevel;
import dev.qcoding.businesscopilot.supportcopilot.draft.JdbcSupportReplyDraftRepository;
import dev.qcoding.businesscopilot.supportcopilot.draft.SupportDraftStatus;
import dev.qcoding.businesscopilot.supportcopilot.draft.SupportReplyDraft;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportEnterpriseService;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportExternalAdapter;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportExternalConnection;
import dev.qcoding.businesscopilot.supportcopilot.integration.SupportExternalProvider;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.evaluation.EvaluationAssertion;
import dev.qcoding.businesscopilot.evaluation.EvaluationCase;
import dev.qcoding.businesscopilot.evaluation.EvaluationEnvironment;
import dev.qcoding.businesscopilot.evaluation.EvaluationHarness;
import dev.qcoding.businesscopilot.evaluation.EvaluationReport;
import dev.qcoding.businesscopilot.evaluation.ExecutionTrace;
import dev.qcoding.businesscopilot.governance.EvaluationManagementService;
import dev.qcoding.businesscopilot.governance.PromptGovernanceService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = false)
class PostgresPgvectorIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty(
                            "business-copilot.test.pgvector-image",
                            "pgvector/pgvector:pg16"))
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("business_copilot_test")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbcTemplate;
    private static DriverManagerDataSource dataSource;
    private static final String LATEST_MIGRATION_VERSION = "42";

    /** 归一化来源在 Prompt 中以 sourceId=<快照 UUID> 形式出现，供脚本化模型按证据引用。 */
    private static final Pattern PROMPT_SOURCE_ID = Pattern.compile(
            "sourceId=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate adminJdbcTemplate = new JdbcTemplate(dataSource);
        adminJdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'business_reader') THEN
                        CREATE ROLE business_reader LOGIN PASSWORD 'reader-test' INHERIT;
                    END IF;
                END
                $$;
                """);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = adminJdbcTemplate;
    }

    @Test
    void runtimePersistsContextEvidenceAttemptsAndBudgetStopAcrossServiceRestart() {
        var store = new JdbcTaskRunStore(jdbcTemplate);
        CurrentActorProvider actor = () -> new CurrentActor("runtime-owner", Set.of(BusinessRole.OPERATOR));
        var runtime = new TaskRunService(store, actor, new DefaultObjectAccessPolicy());
        var manifest = new ContextManifest(List.of("handoff:fictional", "document:version-2"),
                "approval:fictional", Duration.ofHours(1), Instant.now().plusSeconds(3600));
        var run = runtime.startRun("report", "handoff", "persisted", new TaskRunBudget(2, null, 200, null), manifest);
        var step = runtime.beginStep(run.runId(), "generate");
        runtime.recordModelAttempt(run.runId(), step.stepId(), "report.generate", "fixed", "fixture",
                10, 5, 1, TaskAttempt.Outcome.SUCCESS, null);
        runtime.completeStep(step, List.of("source:version-2"), "complete");
        runtime.awaitConfirmation(run.runId(), "wait");
        var restartedStore = new JdbcTaskRunStore(new JdbcTemplate(dataSource));
        var restarted = new TaskRunService(restartedStore, actor, new DefaultObjectAccessPolicy());
        assertThat(restarted.timeline(run.runId()).run().contextManifest().dataScopeRefs())
                .containsExactlyElementsOf(manifest.dataScopeRefs());
        assertThat(restartedStore.findStep(step.stepId()).orElseThrow().evidenceRefs()).containsExactly("source:version-2");
        assertThat(restarted.timeline(run.runId()).steps().getFirst().attemptCount()).isEqualTo(1);
        restarted.resumeWaitingRun(run.runId(), () -> true, () -> true);
        var second = restarted.beginStep(run.runId(), "unknown-usage");
        assertThatThrownBy(() -> restarted.recordModelAttempt(run.runId(), second.stepId(), "gen", "fixed",
                "fixture", null, null, 1, TaskAttempt.Outcome.SUCCESS, null))
                .isInstanceOf(TaskRunService.RunBudgetExhaustedException.class);
        // 业务异常不能回滚已经发生的模型用量及停止状态。
        assertThat(store.findRun(run.runId()).orElseThrow().status()).isEqualTo(TaskRunStatus.BUDGET_EXHAUSTED);
        assertThat(store.findAttempts(run.runId())).hasSize(2);
    }

    @Test
    void runtimeRestartReconcilesStaleDatabaseReservationsWithoutBlindRetry() {
        CurrentActorProvider actor = () -> new CurrentActor(
                "runtime-crash-owner", Set.of(BusinessRole.OPERATOR));
        Instant startedAt = Instant.parse("2026-08-28T10:00:00Z");
        Clock firstClock = Clock.fixed(startedAt, ZoneOffset.UTC);
        var store = new JdbcTaskRunStore(jdbcTemplate);
        var first = new TaskRunService(store, actor, new DefaultObjectAccessPolicy(),
                firstClock, Duration.ofMinutes(15));
        var manifest = new ContextManifest(List.of("fixture:crash-recovery"),
                "fixture-authorization", Duration.ofHours(2), startedAt.plus(Duration.ofHours(2)));

        var modelRun = first.startRun("report", "generation", "crashed-model-db",
                new TaskRunBudget(3, 3, 20_000, Duration.ofHours(1)), manifest);
        var modelStep = first.beginStep(modelRun.runId(), "generate");
        first.aiAttemptObserver(modelRun.runId(), modelStep.stepId())
                .beforeAttempt("report.generate", "fixture", "model", 5_000);

        var toolRun = first.startRun("knowledge", "retrieval", "crashed-tool-db",
                TaskRunBudget.unlimited(), manifest);
        var toolStep = first.beginStep(toolRun.runId(), "retrieve");
        first.beginToolAttempt(toolRun.runId(), toolStep.stepId(), "knowledge.retrieve");

        Clock restartedClock = Clock.fixed(startedAt.plus(Duration.ofMinutes(20)), ZoneOffset.UTC);
        var restarted = new TaskRunService(new JdbcTaskRunStore(new JdbcTemplate(dataSource)),
                actor, new DefaultObjectAccessPolicy(), restartedClock, Duration.ofMinutes(15));
        assertThat(restarted.reconcileInterruptedAttempts()).isEqualTo(2);
        assertThat(restarted.timeline(modelRun.runId()).run().status())
                .isEqualTo(TaskRunStatus.WAITING_CONFIRMATION);
        assertThat(restarted.timeline(modelRun.runId()).attempts()).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(TaskAttempt.Outcome.FAILURE);
                    assertThat(attempt.failureCategory()).isEqualTo(FailureCategory.PROVIDER);
                    assertThat(attempt.inputTokens()).isEqualTo(5_000);
                    assertThat(attempt.outputTokens()).isZero();
                });
        assertThat(restarted.timeline(toolRun.runId()).run().status())
                .isEqualTo(TaskRunStatus.OUTCOME_UNKNOWN);
        assertThat(restarted.timeline(toolRun.runId()).attempts()).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(TaskAttempt.Outcome.UNKNOWN);
                    assertThat(attempt.failureCategory()).isEqualTo(FailureCategory.UNKNOWN_OUTCOME);
                });
        assertThat(restarted.reconcileInterruptedAttempts()).isZero();
    }

    @Test
    void runtimeDatabaseLockAllowsOnlyOneResumeAndConfirmationCallback() throws Exception {
        CurrentActorProvider actor = () -> new CurrentActor("runtime-race", Set.of(BusinessRole.OPERATOR));
        var runtime = new TaskRunService(new JdbcTaskRunStore(jdbcTemplate), actor, new DefaultObjectAccessPolicy());
        var run = runtime.startRun("report", "handoff", "concurrent", TaskRunBudget.unlimited(),
                new ContextManifest(List.of("handoff:race"), "approval:race", Duration.ofHours(1), Instant.now().plusSeconds(3600)));
        runtime.awaitConfirmation(run.runId(), "wait");
        var start = new java.util.concurrent.CountDownLatch(1);
        var confirmations = new java.util.concurrent.atomic.AtomicInteger();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> resume = () -> {
                var instance = new TaskRunService(new JdbcTaskRunStore(new JdbcTemplate(dataSource)),
                        actor, new DefaultObjectAccessPolicy());
                start.await();
                try {
                    instance.resumeWaitingRun(run.runId(), () -> true, () -> {
                        confirmations.incrementAndGet();
                        return true;
                    });
                    return true;
                } catch (BusinessException expected) { return false; }
            };
            var first = workers.submit(resume);
            var second = workers.submit(resume);
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(confirmations).hasValue(1);
    }

    @Test
    void acceptanceHistoryIsAppendOnlyVersionScopedAndBindsServerActor() {
        String version = "acceptance-" + java.util.UUID.randomUUID();
        var service = new AcceptanceEvidenceService(jdbcTemplate,
                () -> new CurrentActor("admin-evidence", Set.of(BusinessRole.ADMIN)), version);
        service.record(new AcceptanceEvidence.Evidence(null, AcceptanceEvidence.Category.MODEL_QUALITY,
                "quality-check", AcceptanceEvidence.Status.PASS, "fixture:old", version + "-old", null,
                "forged-actor", Instant.EPOCH));
        assertThat(service.categorySummaries()).allSatisfy(item ->
                assertThat(item.status()).isEqualTo(AcceptanceEvidence.Status.NOT_VERIFIED));
        var first = service.record(new AcceptanceEvidence.Evidence(null, AcceptanceEvidence.Category.MODEL_QUALITY,
                "quality-check", AcceptanceEvidence.Status.FAILED, "fixture:failure", version, null,
                "forged-actor", Instant.EPOCH));
        assertThat(first.recordedBy()).isEqualTo("admin-evidence");
        assertThat(first.recordedAt()).isAfter(Instant.EPOCH);
        assertThat(service.releaseReadiness().overall()).isEqualTo(AcceptanceEvidence.Status.FAILED);
        service.record(new AcceptanceEvidence.Evidence(null, AcceptanceEvidence.Category.MODEL_QUALITY,
                "quality-check", AcceptanceEvidence.Status.PASS, "fixture:fixed", version, null,
                "forged-actor", Instant.EPOCH));
        assertThat(service.byCategory(AcceptanceEvidence.Category.MODEL_QUALITY))
                .extracting(AcceptanceEvidence.Evidence::status)
                .containsExactly(AcceptanceEvidence.Status.PASS, AcceptanceEvidence.Status.FAILED);
        assertThat(service.categorySummaries().stream()
                .filter(item -> item.category() == AcceptanceEvidence.Category.MODEL_QUALITY).findFirst().orElseThrow().status())
                .isEqualTo(AcceptanceEvidence.Status.PASS);
        assertThat(service.releaseReadiness().releasable()).isFalse();
    }

    @Test
    void migrationsInstallPgvectorAndAuditActors() {
        String extension = jdbcTemplate.queryForObject(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class);
        Integer actorColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = 'actor_id'
                  AND table_name IN (
                    'query_audit_logs',
                    'knowledge_qa_audit_logs',
                    'support_audit_logs',
                    'report_audit_logs',
                    'resume_audit_logs'
                  )
                """, Integer.class);
        Integer httpRequestColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = 'http_request_id'
                  AND table_name IN (
                    'query_audit_logs',
                    'knowledge_qa_audit_logs',
                    'support_audit_logs',
                    'report_audit_logs',
                    'resume_audit_logs'
                  )
                """, Integer.class);
        Integer localeColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = 'locale'
                  AND table_name IN (
                    'query_audit_logs',
                    'knowledge_qa_audit_logs',
                    'support_audit_logs',
                    'report_audit_logs',
                    'resume_audit_logs'
                  )
                """, Integer.class);
        String latestMigration = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class);

        assertThat(extension).isEqualTo("vector");
        assertThat(actorColumns).isEqualTo(5);
        assertThat(httpRequestColumns).isEqualTo(5);
        assertThat(localeColumns).isEqualTo(5);
        assertThat(latestMigration).isEqualTo(LATEST_MIGRATION_VERSION);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name = 'enterprise_readiness_snapshots'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT format_type(atttypid, atttypmod) "
                        + "FROM pg_attribute "
                        + "WHERE attrelid = 'knowledge_chunk_embeddings'::regclass "
                        + "AND attname = 'embedding'",
                String.class)).isEqualTo("vector");
    }

    @Test
    void readinessSnapshotsAreAppendOnlyAndContentSafe() {
        String checks = """
                [{"checkId":"DATA_STALE_HANDOFF_CLAIMS","module":"DATA","status":"PASS",\
                "affectedCount":0,"threshold":"PT15M","actionPath":"/data?tab=handoff"}]
                """;
        Long snapshotId = jdbcTemplate.queryForObject("""
                INSERT INTO enterprise_readiness_snapshots (
                    snapshot_reference, schema_version, purpose, application_version,
                    runtime_mode, status, passed_count, warning_count, blocker_count,
                    checks_json, content_hash, generated_by, generated_at, valid_until
                ) VALUES (gen_random_uuid(), 1, 'integration delivery gate', '2.4.0',
                          'self-hosted', 'READY', 1, 0, 0, ?::jsonb, ?, 'admin-test',
                          now(), now() + interval '24 hours')
                RETURNING id
                """, Long.class, checks, "a".repeat(64));

        assertThat(snapshotId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT checks_json::text FROM enterprise_readiness_snapshots WHERE id = ?",
                String.class, snapshotId))
                .contains("DATA_STALE_HANDOFF_CLAIMS")
                .doesNotContain("\"sql\"", "\"prompt\"", "\"secret\"", "\"content\"");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE enterprise_readiness_snapshots SET purpose = 'changed' WHERE id = ?",
                snapshotId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void readinessProbeExecutesAllStableChecksAgainstTheMigratedSchema() {
        EnterpriseReadinessProperties properties = new EnterpriseReadinessProperties(
                "2.4.0", Duration.ofHours(24), Duration.ofDays(90),
                Duration.ofMinutes(15), Duration.ofHours(1),
                Duration.ofDays(7));

        var counts = new JdbcEnterpriseReadinessProbeRepository(jdbcTemplate)
                .probe(Instant.now(), properties);

        assertThat(counts).hasSize(13);
        assertThat(counts.values()).allMatch(count -> count != null && count >= 0);
        assertThat(counts).containsKeys(
                "DATA_STALE_HANDOFF_CLAIMS", "KNOWLEDGE_BLOCKED_DOCUMENTS",
                "SUPPORT_UNKNOWN_WRITEBACKS", "REPORT_FAILED_RUNS",
                "HR_OVERDUE_ONBOARDING_TASKS");
    }

    @Test
    void readinessBlocksOnFailedIndexEvenThoughTheDocumentIsDisabled() {
        Instant now = Instant.now();
        EnterpriseReadinessProperties properties = new EnterpriseReadinessProperties(
                "2.4.0", Duration.ofHours(24), Duration.ofDays(90),
                Duration.ofMinutes(15), Duration.ofHours(1),
                Duration.ofDays(7));
        JdbcEnterpriseReadinessProbeRepository probes =
                new JdbcEnterpriseReadinessProbeRepository(jdbcTemplate);
        long before = probes.probe(now, properties).get("KNOWLEDGE_BLOCKED_DOCUMENTS");
        insertIndexLifecycleDocument(
                "索引失败文档", "failed-index.txt", "2".repeat(64), "FAILED", false);

        long after = probes.probe(now, properties).get("KNOWLEDGE_BLOCKED_DOCUMENTS");

        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void laterSuccessfulRunsClearRecoverableReadinessFailures() {
        Instant now = Instant.now();
        EnterpriseReadinessProperties properties = new EnterpriseReadinessProperties(
                "2.4.0", Duration.ofHours(24), Duration.ofDays(90),
                Duration.ofMinutes(15), Duration.ofHours(1),
                Duration.ofDays(7));
        JdbcEnterpriseReadinessProbeRepository probes =
                new JdbcEnterpriseReadinessProbeRepository(jdbcTemplate);
        var before = probes.probe(now, properties);
        Long connectionId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_source_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES ('readiness-recovery-source', 'Readiness recovery source', 'NOTION',
                          'https://notion.example.test', 'READINESS_NOTION_TOKEN', TRUE, 'admin-test')
                RETURNING id
                """, Long.class);
        Long scheduleId = jdbcTemplate.queryForObject("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, enabled,
                    owner_actor_id, next_run_at
                ) VALUES ('readiness-recovery-schedule', 'BUSINESS_WEEKLY', 'Readiness report',
                          '0 0 9 * * MON', 'UTC', 'weekly-ops', 'v1',
                          '{"includeSupportMetrics":true}'::jsonb, FALSE,
                          'admin-test', now() + interval '1 day')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO knowledge_sync_runs (
                    connection_id, status, requested_by, started_at, finished_at, error_category
                ) VALUES (?, 'FAILED', 'admin-test', ?, ?, 'UPSTREAM_TIMEOUT')
                """, connectionId, java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.minusSeconds(30)));
        jdbcTemplate.update("""
                INSERT INTO report_schedule_runs (
                    schedule_id, status, reason, started_at, finished_at
                ) VALUES (?, 'FAILED', 'SOURCE_UNAVAILABLE', ?, ?)
                """, scheduleId, java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.minusSeconds(30)));

        var failed = probes.probe(now, properties);
        assertThat(failed.get("KNOWLEDGE_FAILED_SYNC_RUNS"))
                .isEqualTo(before.get("KNOWLEDGE_FAILED_SYNC_RUNS") + 1);
        assertThat(failed.get("REPORT_FAILED_RUNS"))
                .isEqualTo(before.get("REPORT_FAILED_RUNS") + 1);

        jdbcTemplate.update("""
                INSERT INTO knowledge_sync_runs (
                    connection_id, status, requested_by, started_at, finished_at
                ) VALUES (?, 'COMPLETED', 'admin-test', ?, ?)
                """, connectionId, java.sql.Timestamp.from(now.minusSeconds(20)),
                java.sql.Timestamp.from(now.minusSeconds(10)));
        jdbcTemplate.update("""
                INSERT INTO report_schedule_runs (
                    schedule_id, status, started_at, finished_at
                ) VALUES (?, 'DRAFTED', ?, ?)
                """, scheduleId, java.sql.Timestamp.from(now.minusSeconds(20)),
                java.sql.Timestamp.from(now.minusSeconds(10)));

        var recovered = probes.probe(now, properties);
        assertThat(recovered.get("KNOWLEDGE_FAILED_SYNC_RUNS"))
                .isEqualTo(before.get("KNOWLEDGE_FAILED_SYNC_RUNS"));
        assertThat(recovered.get("REPORT_FAILED_RUNS"))
                .isEqualTo(before.get("REPORT_FAILED_RUNS"));
    }

    @Test
    void upgradesV31ToV33WithoutRewritingExistingBusinessData() {
        JdbcTemplate adminJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        adminJdbcTemplate.execute("CREATE DATABASE business_copilot_v31_upgrade_test");
        String upgradeJdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/business_copilot_v31_upgrade_test";
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(upgradeDataSource).target("31").load().migrate();
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        Long auditId = upgradeJdbcTemplate.queryForObject(
                "INSERT INTO query_audit_logs DEFAULT VALUES RETURNING id", Long.class);

        Flyway.configure().dataSource(upgradeDataSource).load().migrate();

        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo(LATEST_MIGRATION_VERSION);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM query_audit_logs WHERE id = ?", Integer.class, auditId))
                .isEqualTo(1);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name = 'enterprise_readiness_snapshots'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void upgradesV32ToV33AndReconcilesDuplicateActiveKnowledgeSyncs() {
        JdbcTemplate adminJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        adminJdbcTemplate.execute("CREATE DATABASE business_copilot_v32_upgrade_test");
        String upgradeJdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/business_copilot_v32_upgrade_test";
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(upgradeDataSource).target("32").load().migrate();
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        Long connectionId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO knowledge_source_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES ('v32-duplicate-sync', 'V32 duplicate sync', 'NOTION',
                          'https://notion.example.test', 'V32_NOTION_TOKEN', TRUE, 'admin-test')
                RETURNING id
                """, Long.class);
        upgradeJdbcTemplate.update("""
                INSERT INTO knowledge_sync_runs(connection_id, status, requested_by, started_at)
                VALUES (?, 'RUNNING', 'admin-old', now() - interval '2 minutes'),
                       (?, 'RUNNING', 'admin-new', now() - interval '1 minute')
                """, connectionId, connectionId);

        Flyway.configure().dataSource(upgradeDataSource).load().migrate();

        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_sync_runs "
                        + "WHERE connection_id = ? AND status = 'RUNNING'",
                Integer.class, connectionId)).isEqualTo(1);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_sync_runs "
                        + "WHERE connection_id = ? AND status = 'CANCELED' "
                        + "AND error_category = 'CONCURRENT_RUN_RECONCILED'",
                Integer.class, connectionId)).isEqualTo(1);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo(LATEST_MIGRATION_VERSION);
    }

    @Test
    void upgradesV38ToV39WithoutLosingFollowupsOrHistoricalInvalidReferences() {
        JdbcTemplate adminJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        adminJdbcTemplate.execute("CREATE DATABASE business_copilot_v38_upgrade_test");
        String upgradeJdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/business_copilot_v38_upgrade_test";
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(upgradeDataSource).target("38").load().migrate();
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);

        Long ticketId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO support_tickets (
                    customer_message, channel, category, sentiment, urgency,
                    status, owner_actor_id
                ) VALUES ('masked message', 'sample', 'OTHER', 'NEUTRAL', 'MEDIUM',
                          'DRAFTED', 'operator-upgrade')
                RETURNING id
                """, Long.class);
        upgradeJdbcTemplate.update("""
                INSERT INTO support_reply_drafts (
                    ticket_id, draft_text, risk_level, expires_at, owner_actor_id, status,
                    original_draft_text, decision_outcome, followup_questions
                ) VALUES (?, 'draft', 'LOW', now() + interval '10 minutes',
                          'operator-upgrade', 'DRAFTED', 'draft', 'PENDING', '["请提供订单号"]'::jsonb)
                """, ticketId);
        upgradeJdbcTemplate.update("""
                INSERT INTO support_quality_cases (
                    ticket_ref, case_type, failure_summary, draft_id, created_by
                ) VALUES ('legacy-orphan', 'OTHER', 'historical invalid reference', 999999, 'reviewer-old')
                """);
        upgradeJdbcTemplate.update("""
                INSERT INTO data_candidate_revisions (
                    candidate_id, root_candidate_id, revised_from, revision_index,
                    question, instruction, actor_id
                ) VALUES ('revision-a', 'root-duplicate', 'root-duplicate', 1,
                          'question', 'first', 'operator-upgrade'),
                         ('revision-b', 'root-duplicate', 'revision-a', 1,
                          'question', 'second', 'operator-upgrade')
                """);
        upgradeJdbcTemplate.update("""
                INSERT INTO hr_candidate_consents (
                    consent_reference, candidate_reference, purpose, purpose_code,
                    granted_at, expires_at, recorded_by
                ) VALUES ('upgrade-consent', 'candidate-upgrade', 'assessment', 'ASSESSMENT',
                          now(), now() + interval '1 day', 'operator-upgrade')
                """);

        Flyway.configure().dataSource(upgradeDataSource).load().migrate();

        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT followup_questions::text FROM support_tickets WHERE id = ?",
                String.class, ticketId)).contains("请提供订单号");
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'support_reply_drafts'
                  AND column_name = 'followup_questions'
                """, Integer.class)).isZero();
        assertThat(upgradeJdbcTemplate.queryForList("""
                SELECT revision_index FROM data_candidate_revisions
                WHERE root_candidate_id = 'root-duplicate' ORDER BY revision_index
                """, Integer.class)).containsExactly(1, 2);
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT retention_expires_at IS NOT NULL FROM hr_candidate_consents
                WHERE consent_reference = 'upgrade-consent'
                """, Boolean.class)).isTrue();
        // NOT VALID 保留历史异常记录，但约束会阻止 V39 之后继续写入悬空引用。
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM support_quality_cases WHERE ticket_ref = 'legacy-orphan'",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                INSERT INTO support_quality_cases (
                    ticket_ref, case_type, failure_summary, draft_id, created_by
                ) VALUES ('new-orphan', 'OTHER', 'must fail', 999998, 'reviewer-new')
                """))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void upgradesAV1DatabaseWithoutLosingKnowledgeDocumentState() {
        JdbcTemplate adminJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        adminJdbcTemplate.execute("CREATE DATABASE business_copilot_upgrade_test");

        String upgradeJdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/business_copilot_upgrade_test";
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(upgradeDataSource).target("7").load().migrate();
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);

        Long indexedDocumentId = insertLegacyDocument(
                upgradeJdbcTemplate, "Indexed document", "b".repeat(64));
        Long indexedChunkId = insertChunk(upgradeJdbcTemplate, indexedDocumentId, 0, "indexed content");
        new JdbcKnowledgeEmbeddingRepository(upgradeJdbcTemplate).saveAll(List.of(
                new KnowledgeChunkEmbedding(null, indexedChunkId, "integration-model", vector(0, 1.0f), null)));
        Long unindexedDocumentId = insertLegacyDocument(
                upgradeJdbcTemplate, "Unindexed document", "c".repeat(64));

        Flyway.configure().dataSource(upgradeDataSource).load().migrate();

        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT enabled FROM knowledge_documents WHERE id = ?", Boolean.class, indexedDocumentId))
                .isTrue();
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT enabled FROM knowledge_documents WHERE id = ?", Boolean.class, unindexedDocumentId))
                .isFalse();
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name IN ('http_request_id', 'actor_id')
                  AND table_name IN (
                    'query_audit_logs',
                    'knowledge_qa_audit_logs',
                    'support_audit_logs',
                    'report_audit_logs',
                    'resume_audit_logs'
                  )
                """, Integer.class)).isEqualTo(10);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo(LATEST_MIGRATION_VERSION);
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = 'locale'
                  AND table_name IN (
                    'query_audit_logs',
                    'knowledge_qa_audit_logs',
                    'support_audit_logs',
                    'report_audit_logs',
                    'resume_audit_logs'
                  )
                """, Integer.class)).isEqualTo(5);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT format_type(atttypid, atttypmod) "
                        + "FROM pg_attribute "
                        + "WHERE attrelid = 'knowledge_chunk_embeddings'::regclass "
                        + "AND attname = 'embedding'",
                String.class)).isEqualTo("vector");
    }

    @Test
    void upgradesV221SchemaToV230WithoutLosingExistingAuditRows() {
        JdbcTemplate adminJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        adminJdbcTemplate.execute("CREATE DATABASE business_copilot_v221_upgrade_test");

        String upgradeJdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/business_copilot_v221_upgrade_test";
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                upgradeJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(upgradeDataSource).target("28").load().migrate();
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        Long auditId = upgradeJdbcTemplate.queryForObject(
                "INSERT INTO query_audit_logs DEFAULT VALUES RETURNING id", Long.class);
        Long supportConnectionId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO support_external_connections (
                    connection_key, display_name, provider, base_url,
                    secret_ref, enabled, owner_actor_id
                ) VALUES ('legacy-jsm', '历史 JSM', 'JIRA_SERVICE_MANAGEMENT',
                          'https://jira.example.test', 'JSM_TOKEN', TRUE, 'operator-1')
                RETURNING id
                """, Long.class);
        Long keeperTicketId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO support_tickets (
                    external_id, customer_message, channel, category, sentiment,
                    urgency, status, owner_actor_id, external_connection_id
                ) VALUES ('EXT-100', '已脱敏的历史问题', 'jira', 'OTHER', 'NEUTRAL',
                          'MEDIUM', 'RECEIVED', 'operator-1', ?)
                RETURNING id
                """, Long.class, supportConnectionId);
        Long duplicateTicketId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO support_tickets (
                    external_id, customer_message, channel, category, sentiment,
                    urgency, status, owner_actor_id, external_connection_id
                ) VALUES ('EXT-100', '并发导入形成的重复行', 'jira', 'OTHER', 'NEUTRAL',
                          'MEDIUM', 'RECEIVED', 'operator-1', ?)
                RETURNING id
                """, Long.class, supportConnectionId);
        Long draftId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO support_reply_drafts (
                    ticket_id, draft_text, risk_level, expires_at, owner_actor_id,
                    status, original_draft_text, decision_outcome
                ) VALUES (?, '已脱敏的历史草稿', 'LOW', now() + interval '1 hour',
                          'operator-1', 'CONFIRMED', '已脱敏的历史草稿', 'ACCEPTED')
                RETURNING id
                """, Long.class, duplicateTicketId);
        Instant contextObservedAt = Instant.parse("2026-07-28T10:00:00Z");
        for (Long ticketId : List.of(keeperTicketId, duplicateTicketId)) {
            upgradeJdbcTemplate.update("""
                    INSERT INTO support_ticket_context_snapshots (
                        ticket_id, context_type, source_reference, sanitized_payload,
                        observed_at, expires_at
                    ) VALUES (?, 'CUSTOMER', 'customer:EXT-100', '{"tier":"standard"}'::jsonb,
                              ?, now() + interval '1 day')
                    """, ticketId, java.sql.Timestamp.from(contextObservedAt));
        }
        Long supportAuditId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO support_audit_logs (
                    ticket_id, event_type, actor_id, creator_actor_id
                ) VALUES (?, 'TICKET_IMPORTED', 'operator-1', 'operator-1')
                RETURNING id
                """, Long.class, duplicateTicketId);
        Long legacyWritebackId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO support_draft_writebacks (
                    draft_id, connection_id, external_ticket_id, payload_hash,
                    status, requested_by, confirmed_by
                ) VALUES (?, ?, 'EXT-100', ?, 'CONFIRMED', 'operator-1', 'operator-1')
                RETURNING id
                """, Long.class, draftId, supportConnectionId, "a".repeat(64));
        Long legacyReportConnectionId = upgradeJdbcTemplate.queryForObject("""
                INSERT INTO report_external_connections (
                    connection_key, display_name, provider, enabled, owner_actor_id
                ) VALUES ('legacy-data-query', '旧版数据查询占位来源',
                          'DATA_QUERY', TRUE, 'operator-1')
                RETURNING id
                """, Long.class);
        upgradeJdbcTemplate.update("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, enabled,
                    owner_actor_id, next_run_at
                ) VALUES ('legacy-placeholder-schedule', 'BUSINESS_WEEKLY', '历史周报',
                          '0 0 9 ? * MON', 'Asia/Shanghai', 'weekly-ops', 'v1',
                          ?::jsonb, TRUE, 'operator-1', now() + interval '1 day')
                """, "{\"connectionIds\":[" + legacyReportConnectionId
                        + "],\"dataHandoffReferences\":[],\"includeSupportMetrics\":false}");
        upgradeJdbcTemplate.update("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, enabled,
                    owner_actor_id, next_run_at
                ) VALUES ('legacy-one-shot-schedule', 'BUSINESS_WEEKLY', '一次性来源周报',
                          '0 0 9 ? * MON', 'Asia/Shanghai', 'weekly-ops', 'v1',
                          '{"connectionIds":[],"dataHandoffReferences":["data-result:old"],'
                          '"includeSupportMetrics":false}'::jsonb,
                          TRUE, 'operator-1', now() + interval '1 day')
                """);

        Flyway.configure().dataSource(upgradeDataSource).load().migrate();

        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo(LATEST_MIGRATION_VERSION);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT locale FROM query_audit_logs WHERE id = ?",
                String.class, auditId)).isEqualTo("zh-CN");
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = 'locale'
                  AND table_name IN (
                    'query_audit_logs',
                    'knowledge_qa_audit_logs',
                    'support_audit_logs',
                    'report_audit_logs',
                    'resume_audit_logs'
                  )
                """, Integer.class)).isEqualTo(5);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT status FROM support_draft_writebacks WHERE id = ?",
                String.class, legacyWritebackId)).isEqualTo("UNKNOWN");
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT error_category FROM support_draft_writebacks WHERE id = ?",
                String.class, legacyWritebackId)).isEqualTo("LEGACY_EXTERNAL_OUTCOME_UNKNOWN");
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM support_tickets
                WHERE external_connection_id = ? AND external_id = 'EXT-100'
                """, Integer.class, supportConnectionId)).isEqualTo(1);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT ticket_id FROM support_reply_drafts WHERE id = ?",
                Long.class, draftId)).isEqualTo(keeperTicketId);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT ticket_id FROM support_audit_logs WHERE id = ?",
                Long.class, supportAuditId)).isEqualTo(keeperTicketId);
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM support_ticket_context_snapshots
                WHERE ticket_id = ? AND source_reference = 'customer:EXT-100'
                """, Integer.class, keeperTicketId)).isEqualTo(1);
        assertThat(upgradeJdbcTemplate.queryForObject(
                "SELECT enabled FROM report_external_connections WHERE id = ?",
                Boolean.class, legacyReportConnectionId)).isFalse();
        assertThat(upgradeJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM report_schedules
                WHERE schedule_key IN ('legacy-placeholder-schedule', 'legacy-one-shot-schedule')
                  AND enabled = TRUE
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                UPDATE report_external_connections SET enabled = TRUE WHERE id = ?
                """, legacyReportConnectionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                UPDATE report_schedules SET enabled = TRUE
                WHERE schedule_key = 'legacy-one-shot-schedule'
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> upgradeJdbcTemplate.update("""
                INSERT INTO support_tickets (
                    external_id, customer_message, channel, category, sentiment,
                    urgency, status, owner_actor_id, external_connection_id
                ) VALUES ('EXT-100', '重复身份', 'jira', 'OTHER', 'NEUTRAL',
                          'MEDIUM', 'RECEIVED', 'operator-1', ?)
                """, supportConnectionId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void enterpriseExpansionMigrationsCreateAllFiveModuleBoundaries() {
        List<String> expected = List.of(
                "data_metric_definitions",
                "data_query_templates",
                "data_schema_snapshots",
                "data_query_results",
                "data_report_handoffs",
                "data_governance_actions",
                "knowledge_source_connections",
                "knowledge_sync_runs",
                "knowledge_source_items",
                "support_external_connections",
                "support_ticket_context_snapshots",
                "support_draft_writebacks",
                "report_external_connections",
                "report_schedules",
                "report_schedule_runs",
                "report_export_audit",
                "hr_candidate_consents",
                "hr_interview_question_bank",
                "hr_interview_sessions",
                "hr_interview_session_members",
                "hr_interview_opinions",
                "hr_ats_connections",
                "hr_ats_imports",
                "hr_onboarding_checklists",
                "hr_onboarding_instances",
                "hr_onboarding_tasks");

        List<String> actual = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name
                """, String.class).stream().filter(expected::contains).toList();

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'knowledge_documents'
                  AND column_name IN (
                    'source_item_ref', 'source_updated_at',
                    'expires_at', 'conflict_status'
                  )
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'resume_submissions'
                  AND column_name IN ('consent_id', 'candidate_reference')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'hr_onboarding_tasks'
                  AND column_name IN ('guidance', 'required', 'owner_role', 'due_at')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND ((table_name = 'report_drafts' AND column_name = 'review_due_at')
                    OR (table_name = 'resume_assessments' AND column_name = 'review_due_at'))
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void governanceCatalogReadModelsBindNestedQueryParameters() {
        CurrentActorProvider actor = () -> new CurrentActor(
                "governance-admin", Set.of(BusinessRole.ADMIN));
        var promptGovernance = new PromptGovernanceService(jdbcTemplate, actor);
        var evaluationManagement = new EvaluationManagementService(
                jdbcTemplate,
                new ObjectMapper(),
                actor,
                mock(AiChatService.class),
                promptGovernance,
                Runnable::run, new JdbcTransactionManager(dataSource));

        assertThat(promptGovernance.definitions())
                .isNotEmpty()
                .allSatisfy(definition -> assertThat(definition.versions()).isNotEmpty());
        assertThat(evaluationManagement.datasets())
                .isNotEmpty()
                .allSatisfy(dataset -> assertThat(dataset.versions()).isNotEmpty());
    }

    @Test
    void bundledChinesePromptUpgradeAppendsAnAuditedVersionWithoutRewritingV1() {
        String promptKey = "data-copilot/result-explanation.st";
        TransactionTemplate transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            List<Long> existingDefinitions = jdbcTemplate.queryForList(
                    "SELECT id FROM prompt_definitions WHERE prompt_key = ?", Long.class, promptKey);
            for (Long definitionId : existingDefinitions) {
                jdbcTemplate.update("""
                        UPDATE evaluation_runs SET prompt_version_id = NULL
                        WHERE prompt_version_id IN (
                            SELECT id FROM prompt_versions WHERE definition_id = ?
                        )
                        """, definitionId);
                jdbcTemplate.update(
                        "DELETE FROM prompt_release_audit WHERE definition_id = ?", definitionId);
                jdbcTemplate.update("""
                        UPDATE prompt_definitions
                        SET active_version_id = NULL, previous_version_id = NULL
                        WHERE id = ?
                        """, definitionId);
                jdbcTemplate.update("DELETE FROM prompt_versions WHERE definition_id = ?", definitionId);
                jdbcTemplate.update("DELETE FROM prompt_definitions WHERE id = ?", definitionId);
            }

            Long definitionId = jdbcTemplate.queryForObject("""
                    INSERT INTO prompt_definitions (
                        prompt_key, module_key, display_name, description
                    ) VALUES (?, 'DATA', ?, 'legacy integration seed')
                    RETURNING id
                    """, Long.class, promptKey, promptKey);
            String legacyContent = "Legacy English system prompt";
            Long legacyVersionId = jdbcTemplate.queryForObject("""
                    INSERT INTO prompt_versions (
                        definition_id, version_number, status, content, content_hash,
                        change_note, created_by, reviewed_by, reviewed_at, published_at
                    ) VALUES (?, 1, 'PUBLISHED', ?, ?, 'Initial bundled version',
                              'system', 'system', now(), now())
                    RETURNING id
                    """, Long.class, definitionId, legacyContent, "0".repeat(64));
            jdbcTemplate.update("""
                    UPDATE prompt_definitions SET active_version_id = ?, rollout_percent = 100
                    WHERE id = ?
                    """, legacyVersionId, definitionId);

            CurrentActorProvider actor = () -> new CurrentActor(
                    "governance-admin", Set.of(BusinessRole.ADMIN));
            var service = new PromptGovernanceService(jdbcTemplate, actor);
            service.definitions();

            assertThat(jdbcTemplate.queryForList("""
                    SELECT version_number, status, content
                    FROM prompt_versions WHERE definition_id = ?
                    ORDER BY version_number
                    """, definitionId))
                    .hasSize(2)
                    .satisfiesExactly(
                            v1 -> {
                                assertThat(v1.get("version_number")).isEqualTo(1);
                                assertThat(v1.get("status")).isEqualTo("RETIRED");
                                assertThat(v1.get("content")).isEqualTo(legacyContent);
                            },
                            v2 -> {
                                assertThat(v2.get("version_number")).isEqualTo(2);
                                assertThat(v2.get("status")).isEqualTo("PUBLISHED");
                                assertThat(v2.get("content").toString()).contains("你是一名资深业务分析师");
                            });
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT version.version_number
                    FROM prompt_definitions definition
                    JOIN prompt_versions version ON version.id = definition.active_version_id
                    WHERE definition.id = ?
                    """, Integer.class, definitionId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM prompt_release_audit
                    WHERE definition_id = ? AND action = 'PUBLISHED'
                      AND actor_id = 'system'
                    """, Integer.class, definitionId)).isEqualTo(1);

            status.setRollbackOnly();
        });
    }

    @Test
    void managedEvaluationRecordsEachForbiddenAssertionExactlyOnce() {
        CurrentActorProvider actor = () -> new CurrentActor(
                "evaluation-admin", Set.of(BusinessRole.ADMIN));
        var promptGovernance = new PromptGovernanceService(jdbcTemplate, actor);
        String promptKey = promptGovernance.definitions().getFirst().promptKey();
        AiChatService aiChatService = mock(AiChatService.class);
        when(aiChatService.isModelEnabled()).thenReturn(true);
        when(aiChatService.generateTextWithMetadata(eq("evaluation.prompt"), anyString(), org.mockito.ArgumentMatchers.any(dev.qcoding.businesscopilot.aicore.AiAttemptObserver.class)))
                .thenReturn(new AiInvocationResult<>("safe output",
                        new AiInvocationMetadata("scripted", "scripted-model", "evaluation-request",
                                10, 5, "stop", 20L)));
        var service = new EvaluationManagementService(
                jdbcTemplate, new ObjectMapper(), actor, aiChatService, promptGovernance, Runnable::run, new JdbcTransactionManager(dataSource));

        String datasetKey = "assertions-" + UUID.randomUUID();
        var dataset = service.createDataset(new EvaluationManagementService.DatasetCommand(
                datasetKey, "DATA", "评测断言去重", "Evaluation assertion deduplication", null, null));
        long versionId = dataset.versions().getFirst().id();
        service.saveCase(versionId, null, new EvaluationManagementService.CaseCommand(
                "safe-output", "禁止项只记录一次", "Forbidden assertions appear once",
                EvaluationManagementService.ExecutionType.PROMPT, promptKey, Map.of(),
                Map.of("notContains", List.of("blocked")), List.of("forbidden"),
                true, true, null, 1));
        service.submitVersion(versionId);
        service.reviewVersion(versionId, true, "approved for regression test");
        service.publishVersion(versionId);

        var run = service.startRun(new EvaluationManagementService.RunCommand(
                versionId, null, EvaluationManagementService.Environment.MODEL,
                "assertions-run-" + UUID.randomUUID()));

        assertThat(run.status()).isEqualTo("PASSED");
        assertThat(run.gateDecision()).isEqualTo("ALLOW_RELEASE");
        assertThat(run.results()).singleElement().satisfies(result -> {
            assertThat(result.assertions()).hasSize(2);
            assertThat(result.assertions()).extracting(item -> item.get("expected"))
                    .containsExactlyInAnyOrder("blocked", "forbidden");
        });
    }

    @Test
    void knowledgeSourceAllowsOnlyOneActiveSyncRunPerConnection() {
        Long connectionId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_source_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES ('single-active-sync', 'Single active sync', 'NOTION',
                          'https://notion.example.test', 'NOTION_TEST_TOKEN', TRUE, 'admin-test')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO knowledge_sync_runs(connection_id, status, requested_by)
                VALUES (?, 'RUNNING', 'admin-test')
                """, connectionId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO knowledge_sync_runs(connection_id, status, requested_by)
                VALUES (?, 'RUNNING', 'admin-test-2')
                """, connectionId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void confirmedFullResyncRecoversAStaleActiveSyncRun() {
        Long connectionId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_source_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES ('stale-sync-recovery', 'Stale sync recovery', 'NOTION',
                          'https://notion.example.test', 'NOTION_TEST_TOKEN', TRUE, 'admin-test')
                RETURNING id
                """, Long.class);
        Long staleRunId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_sync_runs(
                    connection_id, status, requested_by, started_at
                ) VALUES (?, 'RUNNING', 'admin-test', now() - interval '16 minutes')
                RETURNING id
                """, Long.class, connectionId);
        KnowledgeSourceAdapter adapter = new KnowledgeSourceAdapter() {
            @Override
            public boolean supports(KnowledgeSourceProvider provider) {
                return provider == KnowledgeSourceProvider.NOTION;
            }

            @Override
            public SourceBatch fetch(KnowledgeSourceConnection connection, String cursor) {
                return new SourceBatch(List.of(), null, false);
            }
        };
        ExternalEndpointPolicy endpointPolicy = mock(ExternalEndpointPolicy.class);
        when(endpointPolicy.properties()).thenReturn(new ExternalConnectionSecurityProperties(
                List.of(), false, false, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(10), 1024, 2, 50, 16));
        KnowledgeSourceSyncService service = new KnowledgeSourceSyncService(
                jdbcTemplate, List.of(adapter), mock(DocumentUploadService.class),
                () -> new CurrentActor("admin-test", Set.of(BusinessRole.ADMIN)),
                mock(ExternalSecretResolver.class), new ObjectMapper(), endpointPolicy,
                Duration.ofMinutes(15));

        KnowledgeSourceSyncService.SyncResult result = service.synchronize(connectionId, true);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_sync_runs WHERE id = ?",
                String.class, staleRunId)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_category FROM knowledge_sync_runs WHERE id = ?",
                String.class, staleRunId)).isEqualTo("STALE_SYNC_RECOVERED");
    }

    @Test
    void expiredSupportReviewCredentialIsRenewedWithAtomicReviewerClaim() {
        Long ticketId = jdbcTemplate.queryForObject("""
                INSERT INTO support_tickets (
                    external_id, customer_message, channel, category, sentiment,
                    urgency, status, owner_actor_id
                ) VALUES ('RENEW-CREDENTIAL-1', '已脱敏的凭证续期测试', 'local',
                          'OTHER', 'NEUTRAL', 'MEDIUM', 'NEEDS_HUMAN', 'operator-1')
                RETURNING id
                """, Long.class);
        JdbcSupportReplyDraftRepository repository = new JdbcSupportReplyDraftRepository(
                jdbcTemplate, new SensitiveTextMasker());
        SupportReplyDraft draft = repository.save(new SupportReplyDraft(
                null, ticketId, "仅供复核的脱敏草稿", "chunk-1", SupportRiskLevel.MEDIUM,
                "人工复核", null, "old-digest", SupportDraftStatus.NEEDS_REVIEW,
                "operator-1", true, null, null,
                Instant.now().minusSeconds(1), null, null));
        Instant now = Instant.now();
        Instant renewedExpiry = now.plus(Duration.ofMinutes(10));

        assertThat(repository.replaceConfirmationToken(
                draft.id(), SupportDraftStatus.NEEDS_REVIEW, null,
                "new-digest", "reviewer-1", renewedExpiry, now)).isTrue();
        assertThat(repository.replaceConfirmationToken(
                draft.id(), SupportDraftStatus.NEEDS_REVIEW, null,
                "racing-digest", "reviewer-2", renewedExpiry, now)).isFalse();

        SupportReplyDraft claimed = repository.findById(draft.id()).orElseThrow();
        assertThat(claimed.tokenDigest()).isEqualTo("new-digest");
        assertThat(claimed.reviewerActorId()).isEqualTo("reviewer-1");
        assertThat(claimed.expiresAt()).isAfter(now.plus(Duration.ofMinutes(9)));
    }

    @Test
    void enterpriseGovernanceObjectsPersistAndDatabaseConstraintsFailClosed() {
        jdbcTemplate.update("""
                INSERT INTO data_metric_definitions (
                    metric_key, display_name, description, expression_sql, owner_actor_id
                ) VALUES ('paid-order-rate', '支付订单率', '已支付订单占全部订单的比例',
                          'SUM(paid_orders) / NULLIF(SUM(total_orders), 0)', 'operator-1')
                """);
        Long knowledgeConnectionId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_source_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    default_visibility, enabled, owner_actor_id
                ) VALUES ('sharepoint-ops', '运营知识库', 'SHAREPOINT',
                          'https://sharepoint.example.test', 'SHAREPOINT_TOKEN',
                          'ADMIN', TRUE, 'admin')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO knowledge_source_items (
                    connection_id, source_item_id, acl_snapshot,
                    visibility_scope, sync_status
                ) VALUES (?, 'refund-policy', '["unknown-group"]'::jsonb, 'ADMIN', 'CURRENT')
                """, knowledgeConnectionId);
        jdbcTemplate.update("""
                INSERT INTO support_external_connections (
                    connection_key, display_name, provider, base_url,
                    secret_ref, enabled, owner_actor_id
                ) VALUES ('jsm-prod', 'JSM 工单', 'JIRA_SERVICE_MANAGEMENT',
                          'https://jira.example.test', 'JSM_TOKEN', TRUE, 'operator-1')
                """);
        jdbcTemplate.update("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, enabled,
                    owner_actor_id, next_run_at
                ) VALUES ('weekly-ops', 'BUSINESS_WEEKLY', '经营周报 {date}', '0 0 9 * * MON',
                          'Asia/Shanghai', 'weekly-ops', 'v1',
                          '{"includeSupportMetrics":true}'::jsonb, TRUE,
                          'operator-1', now() + interval '1 day')
                """);
        jdbcTemplate.update("""
                INSERT INTO hr_candidate_consents (
                    consent_reference, candidate_reference, purpose, purpose_code,
                    granted_at, expires_at, recorded_by
                ) VALUES ('consent-001', 'candidate-001', '面试评估', 'ASSESSMENT',
                          now(), now() + interval '7 days', 'hr-reviewer-1')
                """);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_metric_definitions WHERE metric_key = 'paid-order-rate'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT visibility_scope FROM knowledge_source_items WHERE source_item_id = 'refund-policy'",
                String.class)).isEqualTo("ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_schedules WHERE enabled = TRUE",
                Integer.class)).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO hr_candidate_consents (
                    consent_reference, candidate_reference, purpose, purpose_code,
                    granted_at, expires_at, recorded_by
                ) VALUES ('invalid-consent', 'candidate-002', '面试评估', 'ASSESSMENT',
                          now(), now() - interval '1 minute', 'hr-reviewer-1')
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO support_external_connections (
                    connection_key, display_name, provider, base_url,
                    secret_ref, enabled, owner_actor_id
                ) VALUES ('unsafe-provider', '未知系统', 'ARBITRARY_HTTP',
                          'https://unsafe.example.test', 'TOKEN', TRUE, 'operator-1')
                """)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void scenarioCatalogUpsertIsIdempotentAndRejectsStaleSampleResults() {
        DemoScenarioRepository repository = new DemoScenarioRepository(jdbcTemplate);
        DemoScenario scenario = new DemoScenario(
                "integration-scenario-001",
                DemoModule.KNOWLEDGE,
                "集成测试场景",
                "只用于验证服务端场景目录幂等性",
                "员工一年有多少天年假？",
                List.of(DemoOperation.ASK_KNOWLEDGE),
                "{\"category\":\"HR_POLICY\"}",
                "系统预置虚构员工手册",
                2,
                true,
                true,
                true,
                "a".repeat(64));

        repository.upsert(scenario);
        repository.upsert(scenario);
        assertThat(repository.findEnabled(null))
                .extracting(DemoScenario::scenarioId)
                .contains(scenario.scenarioId());
        assertThat(repository.findEnabled(DemoModule.KNOWLEDGE))
                .extracting(DemoScenario::scenarioId)
                .contains(scenario.scenarioId());
        repository.upsertSampleResult(
                scenario.scenarioId(), 1, "{\"notice\":\"stale\"}", Instant.now(), "b".repeat(64));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demo_scenarios WHERE scenario_id = ?",
                Integer.class, scenario.scenarioId())).isEqualTo(1);
        assertThat(repository.findSampleResult(scenario.scenarioId())).isEmpty();

        repository.upsertSampleResult(
                scenario.scenarioId(), 2, "{\"notice\":\"current\"}", Instant.now(), "c".repeat(64));
        assertThat(repository.findSampleResult(scenario.scenarioId()))
                .get().extracting(DemoScenarioRepository.SampleResultRecord::scenarioVersion)
                .isEqualTo(2);
    }

    @Test
    void completeDemoSeedCanRunTwiceWithoutDuplicatingBusinessRecords() {
        DocumentUploadService documentUploadService = mock(DocumentUploadService.class);
        when(documentUploadService.ingestSystemDocument(
                anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(new DocumentUploadResponse(1L, "系统虚构资料", 1, true, true));
        DemoDataInitializationService service = new DemoDataInitializationService(
                new DemoDataJobRepository(jdbcTemplate),
                new DemoScenarioRepository(jdbcTemplate),
                documentUploadService,
                new PublicDemoInputGuard(),
                mock(CurrentActorProvider.class),
                jdbcTemplate,
                new ObjectMapper(),
                Runnable::run);

        DemoDataInitializationService.SeedSummary first = service.seedAll();
        DemoDataInitializationService.SeedSummary second = service.seedAll();

        assertThat(first).isEqualTo(second);
        assertThat(first.scenarios()).isEqualTo(15);
        assertThat(first.sampleResults()).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM demo_scenarios
                WHERE scenario_id LIKE 'knowledge-%'
                   OR scenario_id LIKE 'support-%'
                   OR scenario_id LIKE 'hr-%'
                   OR scenario_id LIKE 'data-%'
                   OR scenario_id LIKE 'report-%'
                """, Integer.class)).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM support_tickets WHERE id BETWEEN 91001 AND 91003",
                Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_jobs WHERE id = 92001",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_requests WHERE id BETWEEN 93001 AND 93003",
                Integer.class)).isEqualTo(3);
        assertThat(new SupportQueueService(
                jdbcTemplate,
                () -> new CurrentActor("operator", Set.of(BusinessRole.OPERATOR)))
                .find(null, null, null, null, 50)).hasSize(3);
    }

    @Test
    void publicDemoQuotaUsesAtomicDailyLimits() {
        PublicDemoProperties properties = new PublicDemoProperties(
                2, 2, 1, "integration-fingerprint-secret", "Asia/Shanghai",
                Duration.ofHours(24), Duration.ofDays(7), Duration.ofDays(30),
                null, null);
        PublicDemoQuotaService quota = new PublicDemoQuotaService(jdbcTemplate, properties);
        String fingerprint = "integration-client-" + System.nanoTime();

        assertThat(quota.consumeBusinessOperation(fingerprint).remaining()).isEqualTo(1);
        assertThat(quota.consumeBusinessOperation(fingerprint).remaining()).isZero();
        assertThatThrownBy(() -> quota.consumeBusinessOperation(fingerprint))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.PUBLIC_DEMO_LIMIT_REACHED));
    }

    @Test
    void knowledgeFeedbackAndHumanDispositionFormAConcurrencySafeQualityLoop() {
        Long answerId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_qa_audit_logs (
                    request_id, actor_id, creator_actor_id, question, answer_preview,
                    retrieved_chunk_ids, cited_chunk_ids, answer_status
                ) VALUES (?, ?, ?, ?, ?, '11,12', '11', 'ANSWERED')
                RETURNING id
                """, Long.class,
                "knowledge-feedback-" + System.nanoTime(),
                "operator-feedback",
                "operator-feedback",
                "差旅报销上限是多少？",
                "旧制度中的上限为 2000 元。");
        JdbcKnowledgeFeedbackRepository repository =
                new JdbcKnowledgeFeedbackRepository(jdbcTemplate);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO knowledge_answer_feedback (
                    audit_log_id, actor_id, rating, reason
                ) VALUES (?, ?, 'HELPFUL', 'OTHER')
                """, answerId, "operator-feedback"))
                .isInstanceOf(DataAccessException.class);

        assertThat(repository.upsert(
                answerId,
                "another-operator",
                KnowledgeFeedbackRating.NOT_HELPFUL,
                KnowledgeFeedbackReason.INCORRECT,
                "不应成功")).isEmpty();

        var negative = repository.upsert(
                answerId,
                "operator-feedback",
                KnowledgeFeedbackRating.NOT_HELPFUL,
                KnowledgeFeedbackReason.MISSING_EVIDENCE,
                "缺少报销金额依据").orElseThrow();
        assertThat(negative.answerId()).isEqualTo(answerId);
        var pendingIssue = repository.findQualityQueue(0, 100).stream()
                .filter(item -> item.answerId().equals(answerId))
                .findFirst()
                .orElseThrow();
        assertThat(pendingIssue.answerPreview()).isEqualTo("旧制度中的上限为 2000 元。");
        assertThat(pendingIssue.retrievedChunkIds()).isEqualTo("11,12");
        assertThat(pendingIssue.citedChunkIds()).isEqualTo("11");
        assertThat(repository.findQualityQueue(0, 100))
                .extracting(item -> item.answerId())
                .contains(answerId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO knowledge_quality_reviews (
                    audit_log_id, decision, review_note,
                    reviewer_actor_id, reviewed_issue_version, reviewed_issue_at
                ) VALUES (?, 'RESOLVED', ' ', ?, ?, ?)
                """, answerId, "reviewer-1",
                pendingIssue.issueVersion(),
                java.sql.Timestamp.from(pendingIssue.issueUpdatedAt())))
                .isInstanceOf(DataAccessException.class);

        var review = repository.review(
                answerId,
                KnowledgeQualityReviewDecision.KNOWLEDGE_UPDATE_REQUIRED,
                KnowledgeEvidenceAssessment.OUTDATED,
                KnowledgeAnswerAssessment.PARTIALLY_ACCURATE,
                KnowledgeRemediationAction.UPDATE_KNOWLEDGE,
                "需要补充最新报销制度",
                "reviewer-1",
                pendingIssue.issueVersion(),
                pendingIssue.issueUpdatedAt()).orElseThrow();
        assertThat(review.answerId()).isEqualTo(answerId);
        assertThat(review.evidenceAssessment()).isEqualTo(KnowledgeEvidenceAssessment.OUTDATED);
        assertThat(review.answerAssessment()).isEqualTo(KnowledgeAnswerAssessment.PARTIALLY_ACCURATE);
        assertThat(review.remediationAction()).isEqualTo(KnowledgeRemediationAction.UPDATE_KNOWLEDGE);
        assertThat(repository.findQualityQueue(0, 100))
                .extracting(item -> item.answerId())
                .doesNotContain(answerId);
        assertThat(repository.review(
                answerId,
                KnowledgeQualityReviewDecision.DISMISSED,
                KnowledgeEvidenceAssessment.NOT_APPLICABLE,
                KnowledgeAnswerAssessment.NOT_VERIFIABLE,
                KnowledgeRemediationAction.NONE,
                "并发旧页面不应覆盖",
                "reviewer-2",
                pendingIssue.issueVersion(),
                pendingIssue.issueUpdatedAt())).isEmpty();

        repository.upsert(
                answerId,
                "operator-feedback",
                KnowledgeFeedbackRating.NOT_HELPFUL,
                KnowledgeFeedbackReason.OUTDATED,
                "用户补充了新的过期问题").orElseThrow();
        assertThat(repository.findQualityQueue(0, 100))
                .extracting(item -> item.answerId())
                .contains(answerId);
        assertThat(repository.qualityMetrics().knowledgeUpdateRequiredCount()).isPositive();

        var helpful = repository.upsert(
                answerId,
                "operator-feedback",
                KnowledgeFeedbackRating.HELPFUL,
                null,
                "补充资料后已解决").orElseThrow();
        assertThat(helpful.id()).isEqualTo(negative.id());
        assertThat(repository.findQualityQueue(0, 100))
                .extracting(item -> item.answerId())
                .doesNotContain(answerId);
    }

    @Test
    void exampleReaderCanOnlySelectTheSixSampleBusinessTables() {
        List<String> grantedTables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.role_table_grants
                WHERE grantee = 'business_copilot_reader'
                  AND table_schema = 'public'
                  AND privilege_type = 'SELECT'
                ORDER BY table_name
                """, String.class);

        assertThat(grantedTables).containsExactly(
                "customers",
                "marketing_events",
                "order_items",
                "orders",
                "products",
                "refunds");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('business_reader', 'public.customers', 'SELECT')",
                Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('business_reader', 'public.query_audit_logs', 'SELECT')",
                Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('business_reader', 'public.support_tickets', 'SELECT')",
                Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('business_reader', 'public.customers', 'UPDATE')",
                Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_schema_privilege('business_reader', 'public', 'CREATE')",
                Boolean.class)).isFalse();

        DriverManagerDataSource readerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "business_reader", "reader-test");
        JdbcTemplate readerJdbcTemplate = new JdbcTemplate(readerDataSource);

        assertThat(readerJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.customers", Integer.class)).isPositive();
        assertThatThrownBy(() -> readerJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.query_audit_logs", Integer.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> readerJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.support_tickets", Integer.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> readerJdbcTemplate.update(
                "UPDATE public.customers SET name = name"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void dataCopilotMetadataExposesOnlySchemaQualifiedBusinessTables() {
        JdbcSchemaMetadataRepository repository = new JdbcSchemaMetadataRepository(
                jdbcTemplate,
                new DataCopilotSchemaProperties(null, null, null, 0));

        assertThat(repository.findQueryableTableNames()).containsExactly(
                "public.customers",
                "public.marketing_events",
                "public.order_items",
                "public.orders",
                "public.products",
                "public.refunds");
        assertThat(repository.findColumns("public.customers"))
                .extracting(column -> column.name())
                .contains("id", "name", "phone", "email");
        assertThat(repository.tableExists("public.query_audit_logs")).isTrue();
        assertThat(repository.findQueryableTableNames())
                .doesNotContain("public.query_audit_logs", "public.support_tickets");
    }

    @Test
    void pgvectorSimilaritySearchReturnsTheClosestEnabledChunk() {
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO knowledge_documents (
                title, source_type, source_name, category, content_hash, enabled,
                logical_document_id, version_no, current_version, index_status,
                content_type, owner_actor_id, visibility_scope
            ) VALUES (?, 'upload', ?, 'integration-test', ?, TRUE,
                      gen_random_uuid(), 1, TRUE, 'INDEXED',
                      'text/plain', 'integration-test', 'ALL')
            RETURNING id
                """, Long.class, "Vector test", "vector-test.txt", "a".repeat(64));
        Long firstChunkId = insertChunk(documentId, 0, "closest chunk");
        Long secondChunkId = insertChunk(documentId, 1, "distant chunk");
        Long legacyChunkId = insertChunk(documentId, 2, "legacy dimension chunk");

        float[] firstVector = vector(0, 1.0f);
        float[] secondVector = vector(1, 1.0f);
        JdbcKnowledgeEmbeddingRepository repository = new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);
        repository.saveAll(List.of(
                new KnowledgeChunkEmbedding(null, firstChunkId, "integration-model", firstVector, null),
                new KnowledgeChunkEmbedding(null, secondChunkId, "integration-model", secondVector, null),
                new KnowledgeChunkEmbedding(null, legacyChunkId, "legacy-model",
                        new float[]{1.0f, 0.0f, 0.0f}, null)));

        List<KnowledgeEmbeddingRepository.SimilaritySearchResult> results =
                repository.findSimilarChunks(firstVector, "integration-model", 5, 0.5);

        assertThat(results).extracting(KnowledgeEmbeddingRepository.SimilaritySearchResult::chunkId)
                .containsExactly(firstChunkId);
        assertThat(repository.findByChunkId(firstChunkId)).isPresent();
    }

    @Test
    void chineseKeywordSearchFindsEnabledTextOnlyKnowledgeChunk() {
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO knowledge_documents (
                title, source_type, source_name, category, content_hash, enabled,
                logical_document_id, version_no, current_version, index_status,
                index_error_category, content_type, owner_actor_id, visibility_scope
            ) VALUES (?, 'upload', ?, 'integration-test', ?, TRUE,
                      gen_random_uuid(), 1, TRUE, 'INDEXED',
                      'TEXT_SEARCH_ONLY', 'text/plain', 'integration-test', 'ALL')
                RETURNING id
                """, Long.class, "员工手册", "employee-handbook.txt", "d".repeat(64));
        Long chunkId = insertChunk(
                documentId, 0, "年假政策：员工入职满一年可以享受五天带薪年假。");

        JdbcKnowledgeChunkRepository repository = new JdbcKnowledgeChunkRepository(jdbcTemplate);
        List<KnowledgeChunkRepository.TextSearchResult> results = repository.findByKeywordSearch(
                KnowledgeQueryTerms.extract("请问公司年假政策是什么？"), 5);

        assertThat(results)
                .extracting(KnowledgeChunkRepository.TextSearchResult::chunkId)
                .contains(chunkId);
        assertThat(results.getFirst().rank()).isGreaterThan(0);
    }

    @Test
    void oldModelDisabledIndexJobIsRecoveredAfterUpgrade() {
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO knowledge_documents (
                title, source_type, source_name, category, content_hash, enabled,
                logical_document_id, version_no, current_version, index_status,
                index_error_category, content_type, owner_actor_id, visibility_scope
            ) VALUES (?, 'upload', ?, 'integration-test', ?, FALSE,
                      gen_random_uuid(), 1, TRUE, 'FAILED',
                      'MODEL_DISABLED', 'text/plain', 'integration-test', 'ALL')
                RETURNING id
                """, Long.class, "待恢复文档", "recover.txt", "e".repeat(64));
        Long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_index_jobs (
                    document_id, status, attempts, error_category, next_attempt_at
                ) VALUES (?, 'FAILED', 1, 'MODEL_DISABLED', now())
                RETURNING id
                """, Long.class, documentId);

        var claimed = new JdbcKnowledgeIndexJobRepository(jdbcTemplate).claimNext(Instant.now());

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().id()).isEqualTo(jobId);
        assertThat(claimed.orElseThrow().status()).isEqualTo(KnowledgeIndexJobStatus.PROCESSING);
    }

    @Test
    void duplicateIndexEnqueueReturnsTheSingleActiveJob() {
        Long documentId = insertIndexLifecycleDocument(
                "并发入队文档", "concurrent-enqueue.txt", "f".repeat(64), "PENDING", false);
        JdbcKnowledgeIndexJobRepository repository = new JdbcKnowledgeIndexJobRepository(jdbcTemplate);

        var first = repository.enqueue(documentId);
        var second = repository.enqueue(documentId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_index_jobs
                WHERE document_id = ? AND status IN ('PENDING', 'PROCESSING', 'RETRYABLE')
                """, Integer.class, documentId)).isEqualTo(1);
    }

    @Test
    void canceledIndexWorkerCannotReplaceExistingVectors() {
        Long documentId = insertIndexLifecycleDocument(
                "取消租约文档", "canceled-lease.txt", "0".repeat(64), "INDEXED", true);
        Long chunkId = insertChunk(documentId, 0, "旧版本向量内容");
        JdbcKnowledgeEmbeddingRepository embeddings = new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);
        embeddings.saveAll(List.of(new KnowledgeChunkEmbedding(
                null, chunkId, "old-model", vector(0, 1.0f), null)));
        Long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_index_jobs (
                    document_id, status, attempts, next_attempt_at, started_at, finished_at
                ) VALUES (?, 'CANCELED', 1, now(), now(), now())
                RETURNING id
                """, Long.class, documentId);
        JdbcKnowledgeIndexJobRepository jobs = new JdbcKnowledgeIndexJobRepository(jdbcTemplate);
        KnowledgeIndexLifecycleService lifecycle = indexLifecycleService(
                jobs, mock(KnowledgeDocumentRepository.class), embeddings);
        PreparedKnowledgeIndex prepared = preparedIndex(documentId, chunkId, "new-model");

        Boolean committed = new TransactionTemplate(new JdbcTransactionManager(dataSource))
                .execute(status -> lifecycle.completeWithEmbeddings(
                        jobs.findById(jobId).orElseThrow(), prepared, Instant.now()));

        assertThat(committed).isFalse();
        assertThat(embeddings.findByChunkId(chunkId).orElseThrow().embeddingModel())
                .isEqualTo("old-model");
    }

    @Test
    void failedLifecycleCommitRollsBackVectorsAndTaskState() {
        Long documentId = insertIndexLifecycleDocument(
                "事务回滚文档", "lifecycle-rollback.txt", "1".repeat(64), "PROCESSING", false);
        Long chunkId = insertChunk(documentId, 0, "必须保留的旧向量");
        JdbcKnowledgeEmbeddingRepository embeddings = new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);
        embeddings.saveAll(List.of(new KnowledgeChunkEmbedding(
                null, chunkId, "old-model", vector(0, 1.0f), null)));
        Long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_index_jobs (
                    document_id, status, attempts, next_attempt_at, started_at
                ) VALUES (?, 'PROCESSING', 1, now(), now())
                RETURNING id
                """, Long.class, documentId);
        JdbcKnowledgeIndexJobRepository jobs = new JdbcKnowledgeIndexJobRepository(jdbcTemplate);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        when(documents.updateIndexStatus(documentId, "INDEXED", null, true)).thenReturn(false);
        KnowledgeIndexLifecycleService lifecycle = indexLifecycleService(jobs, documents, embeddings);
        PreparedKnowledgeIndex prepared = preparedIndex(documentId, chunkId, "new-model");
        TransactionTemplate transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                lifecycle.completeWithEmbeddings(
                        jobs.findById(jobId).orElseThrow(), prepared, Instant.now())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("documentId=" + documentId);

        assertThat(jobs.findById(jobId).orElseThrow().status())
                .isEqualTo(KnowledgeIndexJobStatus.PROCESSING);
        assertThat(embeddings.findByChunkId(chunkId).orElseThrow().embeddingModel())
                .isEqualTo("old-model");
    }

    @Test
    void queryAuditPersistsTheHttpRequestAndAuthenticatedActor() {
        BusinessRequestContextHolder.set(new BusinessRequestContext("http-request-001", "operator-1"));
        try {
            JdbcQueryAuditRepository repository = new JdbcQueryAuditRepository(jdbcTemplate);
            Long auditId = repository.save(new AuditEvent(
                    "workflow-request-001",
                    AuditEventType.QUERY_SUCCESS,
                    "top customers",
                    "SELECT id FROM customers LIMIT 5",
                    "SELECT id FROM customers LIMIT 5",
                    AuditStatus.EXECUTED,
                    null,
                    true,
                    0,
                    null,
                    "integration-model",
                    10L));

            java.util.Map<String, Object> audit = jdbcTemplate.queryForMap(
                    "SELECT request_id, http_request_id, actor_id FROM query_audit_logs WHERE id = ?", auditId);
            assertThat(audit.get("request_id")).isEqualTo("workflow-request-001");
            assertThat(audit.get("http_request_id")).isEqualTo("http-request-001");
            assertThat(audit.get("actor_id")).isEqualTo("operator-1");
        } finally {
            BusinessRequestContextHolder.clear();
        }
    }

    private static Long insertChunk(Long documentId, int index, String content) {
        return insertChunk(jdbcTemplate, documentId, index, content);
    }

    private static Long insertIndexLifecycleDocument(String title,
                                                     String sourceName,
                                                     String contentHash,
                                                     String indexStatus,
                                                     boolean enabled) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_documents (
                    title, source_type, source_name, category, content_hash, enabled,
                    logical_document_id, version_no, current_version, index_status,
                    content_type, owner_actor_id, visibility_scope
                ) VALUES (?, 'upload', ?, 'integration-test', ?, ?,
                          gen_random_uuid(), 1, TRUE, ?,
                          'text/plain', 'integration-test', 'ALL')
                RETURNING id
                """, Long.class, title, sourceName, contentHash, enabled, indexStatus);
    }

    private static KnowledgeIndexLifecycleService indexLifecycleService(
            JdbcKnowledgeIndexJobRepository jobs,
            KnowledgeDocumentRepository documents,
            JdbcKnowledgeEmbeddingRepository embeddings) {
        KnowledgeEmbeddingService embeddingService = new KnowledgeEmbeddingService(
                mock(AiEmbeddingService.class), embeddings,
                new KnowledgeCopilotProperties(true, 0, 5, 0.70d, "new-model", 1536));
        return new KnowledgeIndexLifecycleService(jobs, documents, embeddingService);
    }

    private static PreparedKnowledgeIndex preparedIndex(
            Long documentId, Long chunkId, String model) {
        return new PreparedKnowledgeIndex(
                new EmbeddingIndexResult(documentId, 1, model, 1536),
                List.of(new KnowledgeChunkEmbedding(
                        null, chunkId, model, vector(1, 1.0f), null)));
    }

    @Test
    void expandedSampleDataSupportsTrendAndRankingQueries() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers", Integer.class))
                .isGreaterThanOrEqualTo(120);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
                .isGreaterThanOrEqualTo(720);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT date_trunc('month', created_at)) FROM orders",
                Integer.class)).isGreaterThanOrEqualTo(12);
    }

    /** X-01：Data→Report 首次模型超时后恢复成功，交接只消费一次，恰好一份待复核草稿。 */
    @Test
    void dataHandoffTimeoutReleasesHandoffAndRecoveryConsumesItIntoOneReviewableDraft() {
        CurrentActorProvider actor = () -> new CurrentActor("handoff-operator", Set.of(BusinessRole.OPERATOR));
        ObjectMapper objectMapper = new ObjectMapper();

        // Data 侧真实服务：先落一条已消费的 SQL 候选（结果表外键要求），再保存查询结果并创建一次性交接。
        String candidateId = "x01-candidate-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO data_sql_candidates (candidate_id, sql_text, status, owner_actor_id, expires_at)
                VALUES (?, 'SELECT count(*) AS order_count FROM demo_orders', 'CONSUMED', ?, now() + interval '1 day')
                """, candidateId, "handoff-operator");
        DataQueryResultService dataResults = new DataQueryResultService(
                jdbcTemplate, objectMapper, actor, Duration.ofHours(24));
        long resultId = dataResults.save(candidateId,
                new QueryResultTable(List.of(new QueryColumn("order_count", "integer")),
                        List.of(new QueryRow(Map.of("order_count", 42))), 1, false),
                ResultExplanationResponse.success("订单量 42，为固定测试结果。"));
        DataQueryResultService.Handoff handoff =
                dataResults.createReportHandoff(resultId, "X-01 经营快照");

        // Report 侧真实生成链；仅脚本化模型：首次超时，第二次返回引用交接证据的合法输出。
        ReportCopilotProperties properties = new ReportCopilotProperties(true, 0, 0, 0, 0, 0, 0,
                null, null, true);
        ReportRequestPreparationService preparation = new ReportRequestPreparationService(
                new ReportRequestValidator(properties), new ReportSourceMapper(),
                new ReportSourceNormalizer(new SensitiveTextMasker(), properties));
        AiChatService aiChatService = mock(AiChatService.class);
        when(aiChatService.modelName()).thenReturn("scripted-model");
        AtomicInteger scriptedCalls = new AtomicInteger();
        when(aiChatService.generateEvidenceJsonWithMetadata(anyString(), anyString(), eq(LlmReportOutput.class)))
                .thenAnswer(invocation -> {
                    if (scriptedCalls.incrementAndGet() == 1) {
                        throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                                "AI 模型输出无法转换为预期结构",
                                new java.net.SocketTimeoutException("scripted timeout"));
                    }
                    String prompt = invocation.getArgument(1, String.class);
                    List<String> sourceIds = new java.util.ArrayList<>();
                    Matcher sourceIdMatcher = PROMPT_SOURCE_ID.matcher(prompt);
                    while (sourceIdMatcher.find()) {
                        sourceIds.add(sourceIdMatcher.group(1));
                    }
                    return new AiInvocationResult<>(new LlmReportOutput(
                            "订单量为 7。",
                            List.of(sourceIds.getFirst()), List.of(), List.of(), List.of(),
                            List.of(), List.of(),
                            List.of(new ReportCitation(sourceIds.getFirst(), "Data 交接全量行来源"))),
                            new AiInvocationMetadata("scripted", "scripted-model", "x01-request",
                                    12, 34, "stop", 40L));
                });
        ReportGenerationService generationService = new ReportGenerationService(preparation,
                aiChatService, new PromptTemplateService(), new ReportPromptContextFactory(),
                new ReportGenerationOutputValidator(),
                new ReportOutputSanitizer(new SensitiveTextMasker()),
                new ReportDraftPersistenceService(
                        new JdbcReportDraftRepository(jdbcTemplate, actor,
                                new ConfirmationTokenService(), objectMapper, properties.reviewSla()),
                        new ReportAuditService(jdbcTemplate), properties));
        ReportEnterpriseService reportService = new ReportEnterpriseService(jdbcTemplate,
                generationService, actor, mock(ExternalSecretResolver.class), objectMapper,
                mock(ExternalEndpointPolicy.class), mock(ExternalHttpClientFactory.class));

        // Runtime 关联：两次模型尝试必须归属同一运行并共享预算。
        TaskRunService runtime = new TaskRunService(
                new JdbcTaskRunStore(jdbcTemplate), actor, new DefaultObjectAccessPolicy());
        var manifest = new ContextManifest(List.of("handoff:" + handoff.sourceReference()),
                "approval:x01", Duration.ofHours(1), Instant.now().plusSeconds(3600));
        TaskRun run = runtime.startRun("report", "handoff", handoff.sourceReference(),
                new TaskRunBudget(3, null, null, null), manifest);
        TaskStep step = runtime.beginStep(run.runId(), "report-generate-draft");
        var command = new ReportEnterpriseService.GenerateCommand(ReportType.TEAM_WEEKLY,
                new ReportPeriod(LocalDate.now().minusDays(6), LocalDate.now()),
                "X-01 数据交接报告",
                new ReportEnterpriseService.SourceSelection(List.of(),
                        List.of(handoff.sourceReference()), false, null),
                null, null);

        // 第一次：模型超时 → 交接必须释放回 READY，不产生草稿，失败按 PROVIDER 记入账本。
        assertThatThrownBy(() -> reportService.generate(command)).isInstanceOf(BusinessException.class);
        runtime.recordModelAttempt(run.runId(), step.stepId(), "report.generation", "scripted",
                "scripted-model", null, null, 1_200, TaskAttempt.Outcome.FAILURE, FailureCategory.PROVIDER);

        assertThat(handoffStatus(handoff.sourceReference())).isEqualTo("READY");
        assertThat(handoffConsumedAtIsNull(handoff.sourceReference())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_drafts WHERE owner_actor_id = 'handoff-operator'",
                Integer.class)).isZero();

        // 恢复：第二次成功 → 交接只消费一次，恰好产生一份待复核草稿。
        var response = reportService.generate(command);
        runtime.recordModelAttempt(run.runId(), step.stepId(), "report.generation", "scripted",
                "scripted-model", 12, 34, 900, TaskAttempt.Outcome.SUCCESS, null);
        runtime.completeStep(step, List.of("handoff:" + handoff.sourceReference()),
                "交接消费成功并生成待复核草稿");
        runtime.completeRun(run.runId());

        assertThat(response.status()).isEqualTo("DRAFTED");
        assertThat(response.content()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_drafts WHERE owner_actor_id = 'handoff-operator'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM report_drafts WHERE owner_actor_id = 'handoff-operator'",
                String.class)).isEqualTo("DRAFTED");
        assertThat(handoffStatus(handoff.sourceReference())).isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT consumed_at IS NOT NULL AND claim_token IS NULL
                FROM data_report_handoffs WHERE source_reference = ?
                """, Boolean.class, handoff.sourceReference())).isTrue();

        // 运行时间线：两次尝试归属同一运行，失败类别与用量可检查，预算未耗尽。
        var timeline = runtime.timeline(run.runId());
        assertThat(timeline.run().status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(timeline.attempts()).hasSize(2);
        assertThat(timeline.attempts().get(0).outcome()).isEqualTo(TaskAttempt.Outcome.FAILURE);
        assertThat(timeline.attempts().get(0).failureCategory()).isEqualTo(FailureCategory.PROVIDER);
        assertThat(timeline.attempts().get(0).inputTokens()).isNull();
        assertThat(timeline.attempts().get(1).outcome()).isEqualTo(TaskAttempt.Outcome.SUCCESS);
        assertThat(timeline.attempts().get(1).inputTokens()).isEqualTo(12);
        assertThat(timeline.steps().getFirst().attemptCount()).isEqualTo(2);
        assertThat(runtime.budgetExhausted(run.runId())).isFalse();
    }

    /** S-05/S-06：写回超时保持未知；重复确认不重发，必须先按幂等键核对回执。 */
    @Test
    void supportWritebackUnknownOutcomeRequiresReceiptReconciliationBeforeAnyRetry() {
        String suffix = UUID.randomUUID().toString();
        Long connectionId = jdbcTemplate.queryForObject("""
                INSERT INTO support_external_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES (?, 'S05 test', 'JIRA_SERVICE_MANAGEMENT',
                          'https://support.example.test', 'secret-ref', TRUE, 'support-writeback-owner')
                RETURNING id
                """, Long.class, "s05-" + suffix);
        Long ticketId = jdbcTemplate.queryForObject("""
                INSERT INTO support_tickets (
                    external_id, customer_message, channel, category, sentiment, urgency,
                    status, owner_actor_id, external_connection_id
                ) VALUES (?, 'masked test message', 'JSM', 'OTHER', 'NEUTRAL', 'MEDIUM',
                          'CONFIRMED', 'support-writeback-owner', ?)
                RETURNING id
                """, Long.class, "ticket-" + suffix, connectionId);
        Long draftId = jdbcTemplate.queryForObject("""
                INSERT INTO support_reply_drafts (
                    ticket_id, draft_text, risk_level, expires_at, owner_actor_id,
                    status, original_draft_text, decision_outcome
                ) VALUES (?, 'sanitized confirmed reply', 'LOW', now() + interval '1 hour',
                          'support-writeback-owner', 'CONFIRMED',
                          'sanitized confirmed reply', 'ACCEPTED')
                RETURNING id
                """, Long.class, ticketId);

        AtomicInteger writes = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<java.util.Optional<SupportExternalAdapter.ExternalWritebackReceipt>>
                receipt = new java.util.concurrent.atomic.AtomicReference<>(java.util.Optional.empty());
        SupportExternalAdapter adapter = new SupportExternalAdapter() {
            @Override
            public boolean supports(SupportExternalProvider provider) {
                return provider == SupportExternalProvider.JIRA_SERVICE_MANAGEMENT;
            }

            @Override
            public List<ExternalTicket> fetchRecent(SupportExternalConnection connection, int limit) {
                return List.of();
            }

            @Override
            public void writeConfirmedDraft(SupportExternalConnection connection,
                                            String externalTicketId, String sanitizedDraft,
                                            String idempotencyKey) {
                writes.incrementAndGet();
                throw new RuntimeException("scripted timeout after dispatch");
            }

            @Override
            public java.util.Optional<ExternalWritebackReceipt> fetchWritebackReceipt(
                    SupportExternalConnection connection, String externalTicketId,
                    String idempotencyKey) {
                return receipt.get();
            }
        };
        CurrentActorProvider actor = () -> new CurrentActor(
                "support-writeback-owner", Set.of(BusinessRole.OPERATOR));
        SupportEnterpriseService service = new SupportEnterpriseService(
                jdbcTemplate,
                mock(dev.qcoding.businesscopilot.supportcopilot.ticket.SupportTicketRepository.class),
                List.of(adapter), actor, new ConfirmationTokenService(),
                mock(ExternalSecretResolver.class), new SensitiveTextMasker(),
                new ObjectMapper(), mock(ExternalEndpointPolicy.class),
                new DefaultObjectAccessPolicy(),
                mock(dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService.class));

        SupportEnterpriseService.WritebackIntent intent = service.prepareWriteback(draftId);
        assertThatThrownBy(() -> service.confirmWriteback(intent.id(), intent.confirmationToken()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结果未知");
        assertThat(service.writebackStatus(intent.id()).status()).isEqualTo("UNKNOWN");
        assertThat(writes).hasValue(1);

        // 相同凭证已消费，重复确认不得再次发出外部写请求。
        assertThatThrownBy(() -> service.confirmWriteback(intent.id(), intent.confirmationToken()))
                .isInstanceOf(BusinessException.class);
        assertThat(writes).hasValue(1);
        assertThat(service.refreshWritebackReceipt(intent.id()).status()).isEqualTo("UNKNOWN");

        receipt.set(java.util.Optional.of(
                new SupportExternalAdapter.ExternalWritebackReceipt(true, "receipt-s05")));
        SupportEnterpriseService.WritebackStatus reconciled = service.refreshWritebackReceipt(intent.id());
        assertThat(reconciled.status()).isEqualTo("COMPLETED");
        assertThat(reconciled.externalReceipt()).isEqualTo("receipt-s05");
        assertThat(writes).hasValue(1);
    }

    /** R-05：同一到期调度只能被一个执行者领取；领取阶段不会触发发布或生成。 */
    @Test
    void reportScheduleLeaseAllowsOnlyOneConcurrentClaimAndDoesNotPublish() throws Exception {
        jdbcTemplate.update("""
                UPDATE report_schedules
                SET enabled = FALSE, claim_token = NULL, claimed_at = NULL
                WHERE enabled = TRUE AND next_run_at <= now()
                """);
        String scheduleKey = "r05-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, enabled,
                    owner_actor_id, next_run_at
                ) VALUES (?, 'BUSINESS_WEEKLY', 'R05 report {date}', '0 0 9 * * MON',
                          'Asia/Shanghai', 'r05-template', 'v1',
                          '{"includeSupportMetrics":true}'::jsonb, TRUE,
                          'report-schedule-owner', now() - interval '1 minute')
                """, scheduleKey);
        ReportGenerationService generationService = mock(ReportGenerationService.class);
        ReportEnterpriseService firstService = new ReportEnterpriseService(
                jdbcTemplate, generationService,
                () -> new CurrentActor("report-schedule-owner", Set.of(BusinessRole.OPERATOR)),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                mock(ExternalEndpointPolicy.class), mock(ExternalHttpClientFactory.class));
        ReportEnterpriseService secondService = new ReportEnterpriseService(
                jdbcTemplate, generationService,
                () -> new CurrentActor("report-schedule-owner", Set.of(BusinessRole.OPERATOR)),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                mock(ExternalEndpointPolicy.class), mock(ExternalHttpClientFactory.class));
        java.lang.reflect.Method claim = ReportEnterpriseService.class
                .getDeclaredMethod("claimDueSchedule");
        claim.setAccessible(true);

        java.util.concurrent.CompletableFuture<Object> first =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> invoke(claim, firstService));
        java.util.concurrent.CompletableFuture<Object> second =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> invoke(claim, secondService));
        List<Object> claims = java.util.Arrays.asList(first.join(), second.join());

        assertThat(claims.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM report_schedules
                WHERE schedule_key = ? AND claim_token IS NOT NULL AND claimed_at IS NOT NULL
                """, Integer.class, scheduleKey)).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(generationService);
    }

    /** 英文调度在脱离 HTTP 请求后仍应使用创建时语言，执行结束后不得污染线程上下文。 */
    @Test
    void reportScheduleRestoresPersistedLocaleDuringBackgroundGeneration() {
        jdbcTemplate.update("""
                UPDATE report_schedules
                SET enabled = FALSE, claim_token = NULL, claimed_at = NULL
                WHERE enabled = TRUE AND next_run_at <= now()
                """);
        String scheduleKey = "report-locale-" + UUID.randomUUID();
        Long scheduleId = jdbcTemplate.queryForObject("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, locale, enabled,
                    owner_actor_id, next_run_at
                ) VALUES (?, 'BUSINESS_WEEKLY', 'Weekly report {date}', '0 0 9 * * MON',
                          'UTC', 'weekly-ops', 'v1',
                          '{"includeSupportMetrics":true}'::jsonb, 'en-US', TRUE,
                          'report-schedule-owner', now() - interval '1 minute')
                RETURNING id
                """, Long.class, scheduleKey);
        ReportGenerationService generationService = mock(ReportGenerationService.class);
        when(generationService.generate(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0,
                    dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest.class);
            assertThat(BusinessRequestContextHolder.currentLocale()).isEqualTo("en-US");
            return new ReportDraftResponse(
                    null, request.reportType(), request.period(), request.title(),
                    "NEEDS_REVIEW", null, List.of("Manual review required"),
                    null, null, "test-model", "PENDING");
        });
        ReportEnterpriseService service = new ReportEnterpriseService(
                jdbcTemplate, generationService,
                () -> new CurrentActor("report-schedule-owner", Set.of(BusinessRole.OPERATOR)),
                mock(ExternalSecretResolver.class), new ObjectMapper(),
                mock(ExternalEndpointPolicy.class), mock(ExternalHttpClientFactory.class));

        service.generateDueSchedules();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM report_schedule_runs
                WHERE schedule_id = ? ORDER BY id DESC LIMIT 1
                """, String.class, scheduleId)).isEqualTo("NEEDS_REVIEW");
        assertThat(BusinessRequestContextHolder.current()).isNull();
        org.mockito.Mockito.verify(generationService).generate(any());
    }

    /** KNOW-01：固定问题的召回质量以版本化报告记录并通过门禁（确定性回归层）。 */
    @Test
    void knowledgeRetrievalQualityReportIsVersionedAndPassesGate() throws Exception {
        String category = "know01-" + UUID.randomUUID();
        Long expenseDocId = insertKnowledgeDoc("报销流程说明", "expense-report.txt", category);
        Long travelDocId = insertKnowledgeDoc("差旅政策说明", "travel-report.txt", category);
        Long attendanceDocId = insertKnowledgeDoc("考勤制度说明", "attendance-report.txt", category);
        jdbcTemplate.update("UPDATE knowledge_documents SET enabled = FALSE WHERE id = ?", attendanceDocId);
        Long expenseChunkId = insertChunk(expenseDocId, 0, "报销流程：员工出差结束后五个工作日内提交发票与审批单。");
        Long travelChunkId = insertChunk(travelDocId, 0, "差旅政策：乘坐高铁二等座按照实际票价凭票结算。");
        Long attendanceChunkId = insertChunk(attendanceDocId, 0, "考勤制度：迟到三次以上影响季度考核。");

        JdbcKnowledgeEmbeddingRepository embeddingRepository = new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);
        String embeddingModel = "baseline-model-" + UUID.randomUUID();
        embeddingRepository.saveAll(List.of(
                new KnowledgeChunkEmbedding(null, expenseChunkId, embeddingModel, vector(0, 1.0f), null),
                new KnowledgeChunkEmbedding(null, travelChunkId, embeddingModel, vector(2, 1.0f), null),
                new KnowledgeChunkEmbedding(null, attendanceChunkId, embeddingModel, vector(4, 1.0f), null)));

        AiEmbeddingService embeddings = mock(AiEmbeddingService.class);
        when(embeddings.embed(anyString(), anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(1, String.class);
            if (question.contains("报销")) {
                return vector(0, 1.0f);
            }
            if (question.contains("差旅")) {
                return vector(2, 1.0f);
            }
            return vector(8, 1.0f);
        });
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(embeddings,
                embeddingRepository, new JdbcKnowledgeChunkRepository(jdbcTemplate),
                new KnowledgeCopilotProperties(true, 0, 5, 0.70d, embeddingModel, 1536));

        record BaselineCase(String caseId, String question, Long expectedChunkId) {
        }
        List<BaselineCase> baseline = List.of(
                new BaselineCase("KNOW-R1", "报销流程和发票要求是什么？", expenseChunkId),
                new BaselineCase("KNOW-R2", "差旅乘坐高铁的标准是什么？", travelChunkId),
                new BaselineCase("KNOW-R3", "量子计算机的维护手册在哪里？", null));

        List<EvaluationCase> cases = baseline.stream().<EvaluationCase>map(item -> {
            String expectedTop1 = item.expectedChunkId() == null
                    ? "none" : item.expectedChunkId().toString();
            return EvaluationCase.builder(item.caseId(), "knowledge", "固定问题召回质量基线")
                    .maxModelCalls(1)
                    .assertResult("召回@1 命中预期证据分片",
                            trace -> trace.state("top1").orElse("none").equals(expectedTop1))
                    .assertSafety("禁用文档分片不得进入召回结果",
                            trace -> !Boolean.parseBoolean(trace.state("disabledRecalled").orElse("false")))
                    .build();
        }).toList();

        EvaluationHarness harness = new EvaluationHarness(
                (evaluationCase, environment) -> {
                    String question = baseline.stream()
                            .filter(b -> b.caseId().equals(evaluationCase.caseId()))
                            .findFirst().orElseThrow().question();
                    List<Long> ids = retrieval.retrieve(question, category).stream()
                            .map(item -> item.chunk().id()).toList();
                    ExecutionTrace trace = new ExecutionTrace();
                    trace.action("model-call", "question-embedding", "success");
                    trace.state("top1", ids.isEmpty() ? "none" : ids.getFirst().toString());
                    trace.state("topIds", ids.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(",")));
                    trace.state("disabledRecalled", String.valueOf(ids.contains(attendanceChunkId)));
                    return trace;
                },
                new EvaluationHarness.ReportContext("local-workspace", "scripted-embedding",
                        "answer-generation-v2.0", "evidence-guardrails-v2.0", "tools-v1",
                        "knowledge-retrieval-baseline-v1", "postgres-testcontainer"));

        EvaluationReport report = harness.run(cases, new EvaluationEnvironment() {
        });

        assertThat(report.gatePasses()).isTrue();
        assertThat(report.datasetVersion()).isEqualTo("knowledge-retrieval-baseline-v1");
        Path reportDir = Path.of("target", "evaluation-reports");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("knowledge-retrieval-baseline.md"), report.summary());
    }

    private static Long insertKnowledgeDoc(String title, String fileName, String category) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_documents (
                    title, source_type, source_name, category, content_hash, enabled,
                    logical_document_id, version_no, current_version, index_status,
                    content_type, owner_actor_id, visibility_scope
                ) VALUES (?, 'upload', ?, ?, ?, TRUE,
                          gen_random_uuid(), 1, TRUE, 'INDEXED',
                          'text/plain', 'integration-test', 'ALL')
                RETURNING id
                """, Long.class, title, fileName, category, UUID.randomUUID().toString());
    }

    /** DATA-01：已审批指标进入生成依据；停用后立即退出。 */
    @Test
    void approvedMetricDictionaryFeedsGenerationAndDeactivationRemovesIt() {
        String metricKey = "eval_gmv_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO data_metric_definitions (
                    metric_key, display_name, description, unit, expression_sql,
                    owner_actor_id, approved_by, approved_at, active, version
                ) VALUES (?, '评测成交额', '已支付订单金额合计', '元', 'select sum(total_amount) from orders',
                          'governance-owner', 'approver-1', now(), TRUE, 1)
                """, metricKey);
        MetricDictionaryService dictionary = new MetricDictionaryService(jdbcTemplate);

        assertThat(dictionary.approvedMetrics())
                .anySatisfy(metric -> assertThat(metric.metricKey()).isEqualTo(metricKey));
        assertThat(dictionary.matchingMetrics("评测成交额上个月是多少"))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.version()).isEqualTo(1);
                    assertThat(metric.unit()).isEqualTo("元");
                });

        // 停用（或未审批）的定义不能继续作为生成依据。
        jdbcTemplate.update("UPDATE data_metric_definitions SET active = FALSE WHERE metric_key = ?",
                metricKey);
        assertThat(dictionary.matchingMetrics("评测成交额上个月是多少")).isEmpty();
    }

    /** DATA-04：预算内修正候选；每次修正生成新凭证，旧凭证立即失效，超预算拒绝。 */
    @Test
    void candidateRevisionStaysWithinBudgetAndInvalidatesOldToken() {
        CurrentActorProvider actor = () -> new CurrentActor("sql-revision-owner", Set.of(BusinessRole.OPERATOR));
        SqlCandidateStore store = new JdbcSqlCandidateStore(jdbcTemplate);
        SqlConfirmationService confirmation = new SqlConfirmationService(store,
                new dev.qcoding.businesscopilot.datacopilot.confirmation.DataCopilotConfirmationProperties(10),
                actor, new DefaultObjectAccessPolicy(), new ConfirmationTokenService());
        var promptMetadata = new dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata(
                "data-copilot/sql-generation.st", "v2", "hash");
        var aiMetadata = new dev.qcoding.businesscopilot.aicore.AiInvocationMetadata(
                "scripted", "scripted-model", "req", 10, 20, "stop", 25L);

        SqlGenerationService generation = org.mockito.Mockito.mock(SqlGenerationService.class);
        AtomicInteger generationCalls = new AtomicInteger();
        org.mockito.Mockito.when(generation.generate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    int call = generationCalls.incrementAndGet();
                    // 模拟生成链的真实落库行为：新候选必须真实存在，修正链才能继续。
                    String newCandidateId = "rev-candidate-" + UUID.randomUUID();
                    jdbcTemplate.update("""
                            INSERT INTO data_sql_candidates (
                                candidate_id, sql_text, token_digest, status, owner_actor_id, expires_at
                            ) VALUES (?, 'select 1 limit 100', 'digest', 'PENDING', 'sql-revision-owner', now() + interval '10 minutes')
                            """, newCandidateId);
                    return new SqlGenerationResponse("req-" + call, "统计 2026 年 7 月订单量",
                            "select 1 limit 100", "订单量", List.of(), List.of(), null,
                            true, newCandidateId, "raw-token-" + call,
                            Instant.now().plusSeconds(600), List.of(), List.of(), "APPROVED");
                });
        SqlCandidateRevisionService revisions = new SqlCandidateRevisionService(
                jdbcTemplate, generation, actor,
                new DataEnterpriseProperties(0, false, null, 2));

        // 原始候选（真实确认服务签发一次性凭证）
        SqlCandidate original = confirmation.createExecutableCandidate(
                "select 1 limit 100", "orig-req", "scripted-model", promptMetadata, aiMetadata,
                "sql-guardrails-v2.0");

        // 第一次修正：新候选 + 新凭证，旧候选立即失效。
        var first = revisions.revise(new SqlCandidateRevisionService.RevisionCommand(
                original.candidateId(), "统计 2026 年 7 月订单量", "只统计已支付订单"));
        assertThat(first.revision()).isNotNull();
        assertThat(first.revision().revisionIndex()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM data_sql_candidates WHERE candidate_id = ?",
                String.class, original.candidateId())).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_digest IS NULL FROM data_sql_candidates WHERE candidate_id = ?",
                Boolean.class, original.candidateId())).isTrue();
        // 旧凭证不能再消费（一次性确认链路拒绝已失效候选）。
        assertThatThrownBy(() -> confirmation.confirmAndConsume(
                original.candidateId(), original.confirmationToken()))
                .isInstanceOf(Exception.class);

        // 第二次修正：从链上任一候选继续，index=2 仍在预算内。
        var second = revisions.revise(new SqlCandidateRevisionService.RevisionCommand(
                first.generation().candidateId(), "统计 2026 年 7 月订单量", "排除测试订单"));
        assertThat(second.revision().revisionIndex()).isEqualTo(2);

        // 第三次修正：超出预算（maxCandidateRevisions=2）→ 拒绝。
        assertThatThrownBy(() -> revisions.revise(new SqlCandidateRevisionService.RevisionCommand(
                second.generation().candidateId(), "统计 2026 年 7 月订单量", "再改一次")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.STATE_CONFLICT));

        assertThat(revisions.revisions(rootOf(first))).hasSize(2);
    }

    private String rootOf(SqlCandidateRevisionService.RevisionResponse response) {
        return response.revision().rootCandidateId();
    }

    /** DATA-03：固定数据上比较聚合结果的真实口径（重复、边界日期、空值），不以 SQL 可执行判成功。 */
    @Test
    void dataResultCorrectnessEvaluationsCompareFixedBusinessOutcomes() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS data_eval_fixture");
        jdbcTemplate.execute("""
                CREATE TABLE data_eval_fixture (
                    id BIGSERIAL PRIMARY KEY,
                    order_no VARCHAR(20) NOT NULL,
                    amount NUMERIC(12, 2),
                    paid_at DATE
                )
                """);
        // 固定事实：6 行、1 个重复单号、1 个空金额、1 行 7 月边界之外（6-30）与 7 月首尾各一行。
        jdbcTemplate.update("""
                INSERT INTO data_eval_fixture (order_no, amount, paid_at) VALUES
                ('A-001', 100.00, DATE '2026-06-30'),
                ('A-002', 200.00, DATE '2026-07-01'),
                ('A-003', NULL,   DATE '2026-07-15'),
                ('A-004', 400.00, DATE '2026-07-31'),
                ('A-005', 500.00, DATE '2026-08-01'),
                ('A-002', 200.00, DATE '2026-07-15')
                """);
        List<String> fixtureColumns = List.of(
                "public.data_eval_fixture.id",
                "public.data_eval_fixture.order_no",
                "public.data_eval_fixture.amount",
                "public.data_eval_fixture.paid_at");
        var guardrailsProperties = new GuardrailsProperties(
                List.of("public.data_eval_fixture"), fixtureColumns, List.of(), List.of(),
                100, true, List.of("count", "sum", "avg", "min", "max"));
        SqlGuardrailService guardrails = new SqlGuardrailService(List.of(
                new dev.qcoding.businesscopilot.guardrails.SingleStatementValidator(),
                new dev.qcoding.businesscopilot.guardrails.ReadOnlyStatementValidator(),
                new dev.qcoding.businesscopilot.guardrails.ForbiddenKeywordValidator(),
                new dev.qcoding.businesscopilot.guardrails.SchemaWhitelistValidator(
                        guardrailsProperties.queryableTables()),
                new dev.qcoding.businesscopilot.guardrails.ColumnWhitelistValidator(
                        guardrailsProperties.queryableColumns()),
                new dev.qcoding.businesscopilot.guardrails.FunctionAllowlistValidator(
                        guardrailsProperties.allowedAggregateFunctions()),
                new dev.qcoding.businesscopilot.guardrails.SensitiveFieldValidator(
                        new dev.qcoding.businesscopilot.guardrails.SensitiveFieldPolicy(guardrailsProperties)),
                new dev.qcoding.businesscopilot.guardrails.LimitRequiredValidator(
                        guardrailsProperties.defaultMaxRows(),
                        guardrailsProperties.requireLimit(),
                        guardrailsProperties.allowedAggregateFunctions())));

        // 案例 1：7 月订单数（边界日期包含 07-01 与 07-31，不含 06-30 与 08-01）。
        String monthSql = """
                select count(*) from public.data_eval_fixture
                where paid_at >= date '2026-07-01' and paid_at < date '2026-08-01'
                limit 100
                """;
        // 案例 2：去重口径——重复单号按单号去重。
        String dedupSql = """
                select count(distinct order_no) from public.data_eval_fixture limit 100
                """;
        // 案例 3：空值口径——sum 忽略 NULL，不以 0 参与计数。
        String sumSql = """
                select sum(amount) from public.data_eval_fixture
                where paid_at >= date '2026-07-01' and paid_at < date '2026-08-01'
                limit 100
                """;

        for (String candidateSql : List.of(monthSql, dedupSql, sumSql)) {
            assertThat(guardrails.validate(candidateSql, guardrailsProperties).passed())
                    .as("候选 SQL 必须先通过 guardrails")
                    .isTrue();
        }
        // 实际结果与固定预期一致：不能仅以 SQL 可执行判成功。
        assertThat(jdbcTemplate.queryForObject(monthSql, Long.class)).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject(dedupSql, Long.class)).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(sumSql, java.math.BigDecimal.class))
                .isEqualByComparingTo("800.00");
    }

    /** RUN-01/02 验收：Runtime 内嵌进生产生成链后，账本由业务代码驱动而非测试驱动。 */
    @Test
    void runtimeLedgerIsDrivenByEmbeddedProductionGenerationFlow() {
        CurrentActorProvider actor = () -> new CurrentActor("embedded-runtime-owner", Set.of(BusinessRole.OPERATOR));
        ObjectMapper objectMapper = new ObjectMapper();
        TaskRunService runtime = new TaskRunService(
                new JdbcTaskRunStore(jdbcTemplate), actor, new DefaultObjectAccessPolicy());

        // 与 X-01 相同的真实 Report 链，但生成服务内嵌 runtime；模型第一次超时、第二次成功。
        ReportCopilotProperties properties = new ReportCopilotProperties(true, 0, 0, 0, 0, 0, 0,
                null, null, true);
        ReportRequestPreparationService preparation = new ReportRequestPreparationService(
                new ReportRequestValidator(properties), new ReportSourceMapper(),
                new ReportSourceNormalizer(new SensitiveTextMasker(), properties));
        AiChatService aiChatService = mock(AiChatService.class);
        when(aiChatService.modelName()).thenReturn("scripted-model");
        AtomicInteger embeddedCalls = new AtomicInteger();
        when(aiChatService.generateEvidenceJsonWithMetadata(anyString(), anyString(), eq(LlmReportOutput.class), any()))
                .thenAnswer(invocation -> {
                    dev.qcoding.businesscopilot.aicore.AiAttemptObserver observer = invocation.getArgument(3);
                    String attemptId = observer.beforeAttempt("report.generation", "scripted",
                            "scripted-model", 0);
                    try {
                        if (embeddedCalls.incrementAndGet() == 1) {
                            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                                    "AI 模型输出无法转换为预期结构",
                                    new java.net.SocketTimeoutException("scripted timeout"));
                        }
                        String prompt = invocation.getArgument(1, String.class);
                        Matcher sourceIdMatcher = PROMPT_SOURCE_ID.matcher(prompt);
                        if (!sourceIdMatcher.find()) {
                            throw new IllegalStateException("prompt 未包含来源 ID");
                        }
                        String sourceId = sourceIdMatcher.group(1);
                        AiInvocationMetadata metadata = new AiInvocationMetadata(
                                "scripted", "scripted-model", "req", 15, 40, "stop", 60L);
                        AiInvocationResult<LlmReportOutput> result = new AiInvocationResult<>(
                                new LlmReportOutput("订单量为 7。", List.of(sourceId), List.of(),
                                        List.of(), List.of(), List.of(), List.of(),
                                        List.of(new ReportCitation(sourceId, "Data 交接全量行来源"))),
                                metadata);
                        observer.afterAttempt(attemptId, metadata, null);
                        return result;
                    } catch (RuntimeException failure) {
                        observer.afterAttempt(attemptId, null, failure);
                        throw failure;
                    }
                });
        ReportGenerationService generationService = new ReportGenerationService(preparation,
                aiChatService, new PromptTemplateService(), new ReportPromptContextFactory(),
                new ReportGenerationOutputValidator(),
                new ReportOutputSanitizer(new SensitiveTextMasker()),
                new ReportDraftPersistenceService(
                        new JdbcReportDraftRepository(jdbcTemplate, actor,
                                new ConfirmationTokenService(), objectMapper, properties.reviewSla()),
                        new ReportAuditService(jdbcTemplate), properties),
                runtime);
        ReportEnterpriseService reportService = new ReportEnterpriseService(jdbcTemplate,
                generationService, actor, mock(ExternalSecretResolver.class), objectMapper,
                mock(ExternalEndpointPolicy.class), mock(ExternalHttpClientFactory.class));

        String candidateId = "rt-candidate-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO data_sql_candidates (candidate_id, sql_text, status, owner_actor_id, expires_at)
                VALUES (?, 'select count(*) from demo_orders', 'CONSUMED', ?, now() + interval '1 day')
                """, candidateId, "embedded-runtime-owner");
        DataQueryResultService dataResults = new DataQueryResultService(
                jdbcTemplate, objectMapper, actor, Duration.ofHours(24));
        long resultId = dataResults.save(candidateId,
                new QueryResultTable(List.of(new QueryColumn("order_count", "integer")),
                        List.of(new QueryRow(Map.of("order_count", 7))), 1, false),
                ResultExplanationResponse.success("订单量 7。"));
        DataQueryResultService.Handoff handoff =
                dataResults.createReportHandoff(resultId, "内嵌运行快照");
        var command = new ReportEnterpriseService.GenerateCommand(ReportType.TEAM_WEEKLY,
                new ReportPeriod(LocalDate.now().minusDays(6), LocalDate.now()),
                "内嵌运行关联报告",
                new ReportEnterpriseService.SourceSelection(List.of(),
                        List.of(handoff.sourceReference()), false, null),
                null, null);

        // 第一次：超时且供应商未返回用量 → 尝试按 PROVIDER 记账，运行按预算策略失败关闭。
        Throwable firstFailure = org.assertj.core.api.Assertions.catchThrowable(
                () -> reportService.generate(command));
        assertThat(firstFailure).isInstanceOf(TaskRunService.RunBudgetExhaustedException.class);
        List<TaskRun> stoppedRuns = jdbcTemplate.query(
                "SELECT run_id FROM task_runs WHERE owner_actor_id = ? AND status = 'BUDGET_EXHAUSTED'",
                (rs, rowNum) -> TaskRunStub(rs), "embedded-runtime-owner");
        org.assertj.core.api.Assertions.assertThat(stoppedRuns)
                .as("Runtime failure must remain durable; observed exception: %s", firstFailure)
                .hasSize(1);
        String failedRunId = stoppedRuns.getFirst().runId();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_category FROM task_runs WHERE run_id = ?",
                String.class, failedRunId)).isEqualTo("BUDGET");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_attempts WHERE run_id = ? AND outcome = 'FAILURE'",
                Integer.class, failedRunId)).isEqualTo(1);

        // 第二次：成功 → 新运行 SUCCEEDED，真实用量写入账本，草稿仅一份。
        var response = reportService.generate(command);
        assertThat(response.status()).isEqualTo("DRAFTED");
        List<TaskRun> succeededRuns = jdbcTemplate.query(
                "SELECT run_id FROM task_runs WHERE owner_actor_id = ? AND status = 'SUCCEEDED'",
                (rs, rowNum) -> TaskRunStub(rs), "embedded-runtime-owner");
        org.assertj.core.api.Assertions.assertThat(succeededRuns).hasSize(1);
        String succeededRunId = succeededRuns.getFirst().runId();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_tokens FROM task_attempts WHERE run_id = ?",
                Integer.class, succeededRunId)).isEqualTo(15);
        assertThat(handoffStatus(handoff.sourceReference())).isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_drafts WHERE owner_actor_id = 'embedded-runtime-owner'",
                Integer.class)).isEqualTo(1);
    }

    private static TaskRun TaskRunStub(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskRun(rs.getString("run_id"), null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static String handoffStatus(String sourceReference) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM data_report_handoffs WHERE source_reference = ?",
                String.class, sourceReference);
    }

    private static Boolean handoffConsumedAtIsNull(String sourceReference) {
        return jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NULL FROM data_report_handoffs WHERE source_reference = ?",
                Boolean.class, sourceReference);
    }

    /** KNOW-01 基线：固定问题的检索命中可复现、禁用文档不召回；作为第二个真实用例验证 Runtime 账本。 */
    @Test
    void knowledgeRetrievalBaselineRecallsExpectedEvidenceAndLinksSecondRuntimeUseCase() {
        // 播种三个真实文档：报销、差旅为可用证据，考勤文档禁用（生命周期过滤），向量互不相交。
        // 独立分类避免共享库中其他用例的同义语料进入关键词/文本检索路。
        String category = "know-baseline-" + UUID.randomUUID();
        Long expenseDocId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_documents (
                    title, source_type, source_name, category, content_hash, enabled,
                    logical_document_id, version_no, current_version, index_status,
                    content_type, owner_actor_id, visibility_scope
                ) VALUES (?, 'upload', ?, ?, ?, TRUE,
                          gen_random_uuid(), 1, TRUE, 'INDEXED',
                          'text/plain', 'integration-test', 'ALL')
                RETURNING id
                """, Long.class, "报销流程说明", "expense-policy.txt", category, UUID.randomUUID().toString());
        Long travelDocId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_documents (
                    title, source_type, source_name, category, content_hash, enabled,
                    logical_document_id, version_no, current_version, index_status,
                    content_type, owner_actor_id, visibility_scope
                ) VALUES (?, 'upload', ?, ?, ?, TRUE,
                          gen_random_uuid(), 1, TRUE, 'INDEXED',
                          'text/plain', 'integration-test', 'ALL')
                RETURNING id
                """, Long.class, "差旅政策说明", "travel-policy.txt", category, UUID.randomUUID().toString());
        Long attendanceDocId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_documents (
                    title, source_type, source_name, category, content_hash, enabled,
                    logical_document_id, version_no, current_version, index_status,
                    content_type, owner_actor_id, visibility_scope
                ) VALUES (?, 'upload', ?, ?, ?, TRUE,
                          gen_random_uuid(), 1, TRUE, 'INDEXED',
                          'text/plain', 'integration-test', 'ALL')
                RETURNING id
                """, Long.class, "考勤制度说明", "attendance-policy.txt", category, UUID.randomUUID().toString());
        jdbcTemplate.update("UPDATE knowledge_documents SET enabled = FALSE WHERE id = ?", attendanceDocId);

        Long expenseChunkId = insertChunk(expenseDocId, 0, "报销流程：员工出差结束后五个工作日内提交发票与审批单。");
        Long travelChunkId = insertChunk(travelDocId, 0, "差旅政策：乘坐高铁二等座按照实际票价凭票结算。");
        Long attendanceChunkId = insertChunk(attendanceDocId, 0, "考勤制度：迟到三次以上影响季度考核。");

        JdbcKnowledgeEmbeddingRepository embeddingRepository = new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);
        // 每个用例使用唯一模型名，避免共享库中相同向量的其他用例语料互相召回。
        String embeddingModel = "baseline-model-" + UUID.randomUUID();
        embeddingRepository.saveAll(List.of(
                new KnowledgeChunkEmbedding(null, expenseChunkId, embeddingModel, vector(0, 1.0f), null),
                new KnowledgeChunkEmbedding(null, travelChunkId, embeddingModel, vector(2, 1.0f), null),
                new KnowledgeChunkEmbedding(null, attendanceChunkId, embeddingModel, vector(4, 1.0f), null)));

        // 脚本化问题向量：报销问题命中 vector(0)，差旅问题命中 vector(2)，其他问题无匹配。
        AiEmbeddingService embeddings = mock(AiEmbeddingService.class);
        when(embeddings.embed(anyString(), anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(1, String.class);
            if (question.contains("报销")) {
                return vector(0, 1.0f);
            }
            if (question.contains("差旅")) {
                return vector(2, 1.0f);
            }
            return vector(8, 1.0f);
        });
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(embeddings,
                embeddingRepository, new JdbcKnowledgeChunkRepository(jdbcTemplate),
                new KnowledgeCopilotProperties(true, 0, 5, 0.70d, embeddingModel, 1536));

        List<Long> expenseHit = retrieval
                .retrieve("报销流程和发票要求是什么？", category).stream()
                .map(item -> item.chunk().id()).toList();
        assertThat(expenseHit).containsExactly(expenseChunkId);

        List<Long> travelHit = retrieval
                .retrieve("差旅乘坐高铁的标准是什么？", category).stream()
                .map(item -> item.chunk().id()).toList();
        assertThat(travelHit).containsExactly(travelChunkId);

        // 无匹配向量且无关键词交集 → 检索失败可识别，为区分检索失败与回答失败建立基线。
        assertThat(retrieval.retrieve("量子计算机的维护手册在哪里？", category)).isEmpty();
        // 禁用文档即使向量存在也不得召回（权限与生命周期过滤）。
        assertThat(expenseHit).doesNotContain(attendanceChunkId);
        assertThat(travelHit).doesNotContain(attendanceChunkId);

        // 第二个真实用例：Knowledge 检索纳入 Runtime 账本（成功与证据失败两条路径）。
        CurrentActorProvider actor = () -> new CurrentActor("knowledge-operator", Set.of(BusinessRole.OPERATOR));
        TaskRunService runtime = new TaskRunService(
                new JdbcTaskRunStore(jdbcTemplate), actor, new DefaultObjectAccessPolicy());
        var manifest = new ContextManifest(
                List.of("document:" + expenseDocId, "document:" + travelDocId),
                "approval:knowledge-baseline", Duration.ofHours(1), Instant.now().plusSeconds(3600));

        TaskRun hitRun = runtime.startRun("knowledge", "document", expenseDocId.toString(),
                new TaskRunBudget(null, 2, null, null), manifest);
        TaskStep hitStep = runtime.beginStep(hitRun.runId(), "retrieve-evidence");
        runtime.recordToolAttempt(hitRun.runId(), hitStep.stepId(), "knowledge.retrieve",
                null, null, 5, TaskAttempt.Outcome.SUCCESS, null);
        runtime.completeStep(hitStep, List.of("document:" + expenseDocId), "检索命中预期证据");
        runtime.completeRun(hitRun.runId());
        var hitTimeline = runtime.timeline(hitRun.runId());
        assertThat(hitTimeline.run().status()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(hitTimeline.attempts()).hasSize(1);
        assertThat(hitTimeline.attempts().getFirst().kind()).isEqualTo(TaskAttempt.Kind.TOOL);
        assertThat(hitTimeline.attempts().getFirst().outcome()).isEqualTo(TaskAttempt.Outcome.SUCCESS);
        assertThat(hitTimeline.steps().getFirst().evidenceRefs()).containsExactly("document:" + expenseDocId);

        TaskRun missRun = runtime.startRun("knowledge", "document", attendanceDocId.toString(),
                new TaskRunBudget(null, 2, null, null), manifest);
        TaskStep missStep = runtime.beginStep(missRun.runId(), "retrieve-evidence");
        runtime.failStep(missStep, FailureCategory.EVIDENCE, "检索未召回任何可用证据");
        runtime.failRun(missRun.runId(), FailureCategory.EVIDENCE, "无证据，转人工处理");
        var missTimeline = runtime.timeline(missRun.runId());
        assertThat(missTimeline.run().status()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(missTimeline.run().failureCategory()).isEqualTo(FailureCategory.EVIDENCE);
        assertThat(missTimeline.steps().getFirst().failureCategory()).isEqualTo(FailureCategory.EVIDENCE);
    }

    private static Long insertLegacyDocument(JdbcTemplate target, String title, String contentHash) {
        return target.queryForObject("""
                INSERT INTO knowledge_documents (
                    title, source_type, source_name, category, content_hash, enabled
                ) VALUES (?, 'upload', ?, 'integration-test', ?, TRUE)
                RETURNING id
                """, Long.class, title, title + ".txt", contentHash);
    }

    private static Long insertChunk(JdbcTemplate target, Long documentId, int index, String content) {
        return target.queryForObject("""
                INSERT INTO knowledge_chunks (
                    document_id, section_title, chunk_index, content, content_preview, token_count
                ) VALUES (?, 'test', ?, ?, ?, 2)
                RETURNING id
                """, Long.class, documentId, index, content, content);
    }

    private static float[] vector(int nonZeroIndex, float value) {
        float[] vector = new float[1536];
        vector[nonZeroIndex] = value;
        return vector;
    }

    private static Object invoke(java.lang.reflect.Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("反射调用测试目标失败", cause != null ? cause : ex);
        }
    }
}
