package dev.qcoding.businesscopilot;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import dev.qcoding.businesscopilot.commonsecurity.*;
import dev.qcoding.businesscopilot.commonweb.api.*;
import dev.qcoding.businesscopilot.commonweb.request.*;
import dev.qcoding.businesscopilot.audit.*;
import dev.qcoding.businesscopilot.datacopilot.confirmation.*;
import dev.qcoding.businesscopilot.datacopilot.query.*;
import dev.qcoding.businesscopilot.datacopilot.explanation.*;
import dev.qcoding.businesscopilot.supportcopilot.integration.*;
import dev.qcoding.businesscopilot.reportcopilot.enterprise.*;
import dev.qcoding.businesscopilot.reportcopilot.generation.*;
import dev.qcoding.businesscopilot.reportcopilot.request.*;
import dev.qcoding.businesscopilot.resumecopilot.assessment.*;
import dev.qcoding.businesscopilot.resumecopilot.persistence.*;
import dev.qcoding.businesscopilot.resumecopilot.enterprise.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real PostgreSQL and Spring transactions, with controlled external I/O and deterministic races. */
@org.testcontainers.junit.jupiter.Testcontainers
class BusinessFlowReliabilityPostgresIntegrationTest {
  static JdbcTemplate jdbc;
  static DriverManagerDataSource ds;
  static final CurrentActorProvider ACTOR = () -> new CurrentActor("audit-operator", Set.of(BusinessRole.ADMIN));
  static final DefaultObjectAccessPolicy ACCESS = new DefaultObjectAccessPolicy();
  static final ConfirmationTokenService TOKENS = new ConfirmationTokenService();
  static final ObjectMapper JSON = new ObjectMapper();

  @SuppressWarnings("unchecked")
  static <T> T make(Class<T> type, Object... supplied) throws Exception {
    var ctor = Arrays.stream(type.getConstructors()).max(Comparator.comparingInt(c -> c.getParameterCount())).orElseThrow();
    var args = Arrays.stream(ctor.getParameterTypes()).map(p -> Arrays.stream(supplied).filter(p::isInstance).findFirst().orElseGet(() -> mock(p))).toArray();
    return (T) ctor.newInstance(args);
  }
  @SuppressWarnings("unchecked")
  static <T> T transactional(T target) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(new JdbcTransactionManager(ds), new AnnotationTransactionAttributeSource()));
    return (T)factory.getProxy();
  }
  @org.testcontainers.junit.jupiter.Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
  @org.junit.jupiter.api.BeforeAll
  static void migrate() {
    ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    Flyway.configure().dataSource(ds).load().migrate();
  }
  @org.junit.jupiter.api.BeforeEach
  void context() {
    BusinessRequestContextHolder.set(new BusinessRequestContext("audit-probe", "audit-operator", Set.of("ADMIN"), "zh-CN"));
  }
  @org.junit.jupiter.api.AfterEach
  void clearContext() { BusinessRequestContextHolder.clear(); }
  @org.junit.jupiter.api.Test
  void supportReviewQueueUsesIndependentReviewerScopeWithoutExposingOtherOperators() {
    long ticket = jdbc.queryForObject("""
        INSERT INTO support_tickets(customer_message,channel,category,sentiment,urgency,status,owner_actor_id)
        VALUES ('private fictional question','fixture','OTHER','NEUTRAL','MEDIUM','NEEDS_HUMAN','queue-owner') RETURNING id
        """, Long.class);
    long draft = jdbc.queryForObject("""
        INSERT INTO support_reply_drafts(ticket_id,draft_text,risk_level,expires_at,owner_actor_id,status,review_queue,original_draft_text,decision_outcome)
        VALUES (?,'fictional reply','MEDIUM',now()+interval '10 minutes','queue-owner','NEEDS_REVIEW',true,'fictional reply','PENDING') RETURNING id
        """, Long.class, ticket);
    var reviewer = new dev.qcoding.businesscopilot.supportcopilot.queue.SupportQueueService(jdbc,
        () -> new CurrentActor("queue-reviewer", Set.of(BusinessRole.REVIEWER)));
    var otherOperator = new dev.qcoding.businesscopilot.supportcopilot.queue.SupportQueueService(jdbc,
        () -> new CurrentActor("other-operator", Set.of(BusinessRole.OPERATOR)));
    assertThat(reviewer.find(null,null,null,null,100)).extracting(item -> item.ticketId()).contains(ticket);
    assertThat(otherOperator.find(null,null,null,null,100)).extracting(item -> item.ticketId()).doesNotContain(ticket);
    jdbc.update("UPDATE support_reply_drafts SET reviewer_actor_id='another-reviewer' WHERE id=?", draft);
    assertThat(reviewer.find(null,null,null,null,100)).extracting(item -> item.ticketId()).doesNotContain(ticket);
    jdbc.update("UPDATE support_reply_drafts SET reviewer_actor_id='queue-reviewer' WHERE id=?", draft);
    assertThat(reviewer.find(null,null,null,null,100)).extracting(item -> item.ticketId()).contains(ticket);
    jdbc.update("UPDATE support_reply_drafts SET review_queue=false WHERE id=?", draft);
    assertThat(reviewer.find(null,null,null,null,100)).extracting(item -> item.ticketId()).doesNotContain(ticket);
  }

  @org.junit.jupiter.api.Test
  void failedQueryKeepsConsumedTokenAndCommittedIntent() throws Exception {
    var store = new JdbcSqlCandidateStore(jdbc);
    var confirmation = transactional(new SqlConfirmationService(store, new DataCopilotConfirmationProperties(10), ACTOR, ACCESS, TOKENS));
    var candidate = confirmation.createExecutableCandidate("select 1 limit 1", "audit-data", "fictional query", "fixture");
    AtomicInteger calls = new AtomicInteger(), visibleIntent = new AtomicInteger(-1);
    ReadOnlyQueryExecutor executor = sql -> {
      calls.incrementAndGet();
      visibleIntent.set(CompletableFuture.supplyAsync(() -> new JdbcTemplate(ds).queryForObject(
        "SELECT count(*) FROM query_audit_logs WHERE request_id = 'audit-data'", Integer.class)).join());
      throw new BusinessException(ErrorCode.QUERY_EXECUTION_ERROR, "scripted query timeout");
    };
    var service = transactional(new QueryExecutionService(confirmation, executor, mock(ResultExplanationService.class), new AuditService(new JdbcQueryAuditRepository(jdbc))));
    for (int i=0;i<2;i++) { try { service.execute(candidate.candidateId(), candidate.confirmationToken()); } catch (BusinessException expected) {} }
    String status = store.findById(candidate.candidateId()).status().name();
    int audits = jdbc.queryForObject("SELECT count(*) FROM query_audit_logs WHERE request_id = 'audit-data'", Integer.class);
    assertThat(calls.get()).as("one-time confirmation cannot be replayed after failure").isEqualTo(1);
    assertThat(status).isEqualTo("CONSUMED");
    assertThat(audits).isGreaterThanOrEqualTo(2);
    assertThat(visibleIntent.get()).as("intent must commit before external dispatch").isGreaterThan(0);
  }
  @org.junit.jupiter.api.Test
  void failedIntentRollsBackConsumptionAndPreventsDispatch() {
    var store = new JdbcSqlCandidateStore(jdbc);
    var confirmation = transactional(new SqlConfirmationService(store, new DataCopilotConfirmationProperties(10), ACTOR, ACCESS, TOKENS));
    var candidate = confirmation.createExecutableCandidate("select 1 limit 1", "intent-failure", "fictional query", "fixture");
    var auditRepository = mock(QueryAuditRepository.class);
    when(auditRepository.save(any())).thenThrow(new IllegalStateException("scripted audit outage"));
    var executor = mock(ReadOnlyQueryExecutor.class);
    var service = transactional(new QueryExecutionService(confirmation, executor, mock(ResultExplanationService.class), new AuditService(auditRepository)));
    assertThatThrownBy(() -> service.execute(candidate.candidateId(), candidate.confirmationToken())).isInstanceOf(IllegalStateException.class);
    assertThat(store.findById(candidate.candidateId()).status()).isEqualTo(SqlCandidateStatus.PENDING);
    verifyNoInteractions(executor);
  }

  @org.junit.jupiter.api.Test
  void concurrentConfirmationsDispatchOnlyOnceEvenWhenResultPersistenceFails() throws Exception {
    var store = new JdbcSqlCandidateStore(jdbc);
    var confirmation = transactional(new SqlConfirmationService(store, new DataCopilotConfirmationProperties(10), ACTOR, ACCESS, TOKENS));
    var candidate = confirmation.createExecutableCandidate("select 1 limit 1", "concurrent-data", "fictional query", "fixture");
    AtomicInteger dispatches = new AtomicInteger();
    ReadOnlyQueryExecutor executor = sql -> {
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      dispatches.incrementAndGet();
      return QueryResultTable.empty(List.of());
    };
    var results = mock(dev.qcoding.businesscopilot.datacopilot.enterprise.DataQueryResultService.class);
    when(results.save(anyString(), any(), any())).thenThrow(new IllegalStateException("scripted result store outage"));
    var explanation = mock(ResultExplanationService.class);
    when(explanation.explain(any())).thenReturn(ResultExplanationResponse.success("fixture"));
    var service = transactional(new QueryExecutionService(confirmation, executor, explanation, new AuditService(new JdbcQueryAuditRepository(jdbc)), results));
    raceTwenty(() -> { try { service.execute(candidate.candidateId(), candidate.confirmationToken()); } catch (BusinessException | IllegalStateException expected) { } });
    assertThat(dispatches.get()).isEqualTo(1);
    assertThat(store.findById(candidate.candidateId()).status()).isEqualTo(SqlCandidateStatus.CONSUMED);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM query_audit_logs WHERE request_id='concurrent-data' AND execution_status='QUERY_EXECUTION_INTENT'", Integer.class)).isEqualTo(1);
  }

  static void raceTwenty(Runnable action) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(20)) {
      List<Future<?>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++) calls.add(pool.submit(() -> { start.await(); action.run(); return null; }));
      start.countDown();
      for (var call : calls) call.get(30, TimeUnit.SECONDS);
    }
  }

  @org.junit.jupiter.api.Test
  void lateWritebackPreparationCannotReopenCompletedDispatch() throws Exception {
    long draft = supportDraft();
    AtomicInteger sends = new AtomicInteger();
    var adapter = mock(SupportExternalAdapter.class);
    when(adapter.supports(any())).thenReturn(true);
    doAnswer(inv -> {sends.incrementAndGet();return null;}).when(adapter).writeConfirmedDraft(any(),anyString(),anyString(),anyString());
    var normal = make(SupportEnterpriseService.class,jdbc,ACTOR,TOKENS,ACCESS,JSON,List.of(adapter));
    JdbcTemplate delayed = spy(new JdbcTemplate(ds));
    doAnswer(inv -> {
      // A has passed the initial status read. B prepares and completes before A's upsert.
      var first = normal.prepareWriteback(draft);
      normal.confirmWriteback(first.id(), first.confirmationToken());
      return inv.callRealMethod();
    }).when(delayed).queryForObject(contains("INSERT INTO support_draft_writebacks"),eq(Long.class),any(Object[].class));
    var racing = make(SupportEnterpriseService.class,delayed,ACTOR,TOKENS,ACCESS,JSON,List.of(adapter));
    assertThatThrownBy(() -> racing.prepareWriteback(draft)).isInstanceOf(BusinessException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM support_draft_writebacks WHERE draft_id=?",String.class,draft)).isEqualTo("COMPLETED");
    assertThat(sends.get()).isEqualTo(1);
  }
  static long supportDraft() {
    long connection = jdbc.queryForObject("INSERT INTO support_external_connections (connection_key,display_name,provider,base_url,secret_ref,enabled,owner_actor_id) VALUES (gen_random_uuid()::text,'audit','JIRA_SERVICE_MANAGEMENT','https://fixture.invalid','FIXTURE',true,'audit-operator') RETURNING id", Long.class);
    long ticket = jdbc.queryForObject("INSERT INTO support_tickets (customer_message,channel,category,sentiment,urgency,status,owner_actor_id,external_id,external_connection_id) VALUES ('fictional ticket','fixture','OTHER','NEUTRAL','MEDIUM','DRAFTED','audit-operator','FIXTURE-1',?) RETURNING id", Long.class, connection);
    long draft = jdbc.queryForObject("INSERT INTO support_reply_drafts (ticket_id,draft_text,risk_level,expires_at,owner_actor_id,status,original_draft_text,decision_outcome) VALUES (?,'fictional reply','LOW',now()+interval '10 minutes','audit-operator','CONFIRMED','fictional reply','ACCEPTED') RETURNING id", Long.class, ticket);
    return draft;
  }

  @org.junit.jupiter.api.Test
  void concurrentWritebackPreparationAndConfirmationDispatchOnce() throws Exception {
    long draft = supportDraft();
    AtomicInteger sends = new AtomicInteger();
    var adapter = mock(SupportExternalAdapter.class);
    when(adapter.supports(any())).thenReturn(true);
    doAnswer(inv -> { sends.incrementAndGet(); return null; }).when(adapter).writeConfirmedDraft(any(), anyString(), anyString(), anyString());
    var service = make(SupportEnterpriseService.class, jdbc, ACTOR, TOKENS, ACCESS, JSON, List.of(adapter));
    raceTwenty(() -> {
      try {
        var intent = service.prepareWriteback(draft);
        service.confirmWriteback(intent.id(), intent.confirmationToken());
      } catch (BusinessException expected) { }
    });
    assertThat(sends.get()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM support_draft_writebacks WHERE draft_id=?", String.class, draft)).isEqualTo("COMPLETED");
  }

  @org.junit.jupiter.api.Test
  void lateProviderFailureCannotOverwriteVerifiedReceipt() throws Exception {
    long draft = supportDraft();
    var adapter = mock(SupportExternalAdapter.class);
    when(adapter.supports(any())).thenReturn(true);
    var service = make(SupportEnterpriseService.class, jdbc, ACTOR, TOKENS, ACCESS, JSON, List.of(adapter));
    var intent = service.prepareWriteback(draft);
    when(adapter.fetchWritebackReceipt(any(), anyString(), anyString())).thenReturn(Optional.of(
        new SupportExternalAdapter.ExternalWritebackReceipt(true, "fixture-receipt")));
    doAnswer(inv -> {
      assertThat(service.refreshWritebackReceipt(intent.id()).status()).isEqualTo("COMPLETED");
      throw new IllegalStateException("late transport failure");
    }).when(adapter).writeConfirmedDraft(any(), anyString(), anyString(), anyString());
    assertThat(service.confirmWriteback(intent.id(), intent.confirmationToken()).status()).isEqualTo("COMPLETED");
    assertThat(service.writebackStatus(intent.id()).externalReceipt()).isEqualTo("fixture-receipt");
  }

  @org.junit.jupiter.api.Test
  void supportMetricsRespectSelectedReportPeriod() throws Exception {
    jdbc.update("INSERT INTO support_tickets (customer_message,channel,category,sentiment,urgency,status,owner_actor_id,created_at) VALUES ('historical fictional ticket','fixture','OTHER','NEUTRAL','MEDIUM','CLOSED','audit-operator',now()-interval '60 days')");
    long historical = jdbc.queryForObject("SELECT count(*) FROM support_tickets WHERE created_at < current_date - 6", Long.class);
    long total = jdbc.queryForObject("SELECT count(*) FROM support_tickets",Long.class);
    AtomicReference<ReportGenerateRequest> captured = new AtomicReference<>();
    var generator = mock(ReportGenerationService.class);
    when(generator.generate(any())).thenAnswer(inv -> {
      ReportGenerateRequest req=inv.getArgument(0); captured.set(req);
      return new ReportDraftResponse(null,req.reportType(),req.period(),req.title(),"REJECTED",null,List.of(),null,null,"fixture",null);
    });
    var service=make(ReportEnterpriseService.class,jdbc,generator,ACTOR,JSON);
    service.generate(new ReportEnterpriseService.GenerateCommand(ReportType.BUSINESS_WEEKLY,
      new ReportPeriod(LocalDate.now().minusDays(6),LocalDate.now()),"fictional weekly report",
      new ReportEnterpriseService.SourceSelection(List.of(),List.of(),true,null),"fixture","v1"));
    String supplied=captured.get().importedSources().stream().filter(s -> "support.total".equals(s.attributes().get("name"))).findFirst().orElseThrow().attributes().get("value");
    assertThat(historical).isPositive();
    assertThat(supplied).isEqualTo(String.valueOf(total-historical));
  }
  @org.junit.jupiter.api.Test
  void revokedConsentImmediatelyHidesAssessmentEvidenceAndQueue() throws Exception {
    long[] fixture = resumeFixture();
    long consent = fixture[0], submission = fixture[2], assessment = fixture[3];
    var hr=transactional(make(HrEnterpriseService.class,jdbc,ACTOR,JSON));
    hr.revokeConsent(jdbc.queryForObject("SELECT consent_reference FROM hr_candidate_consents WHERE id=?", String.class, consent));
    CurrentActorProvider reviewer=()->new CurrentActor("audit-reviewer",Set.of(BusinessRole.REVIEWER));
    var service=make(ResumeAssessmentService.class,new ResumeRepository(jdbc),reviewer,ACCESS,JSON,TOKENS);
    assertThatThrownBy(() -> service.reviewView(assessment)).isInstanceOf(BusinessException.class);
    assertThat(service.reviewQueue(100)).extracting(ResumeAssessmentService.ReviewQueueItem::assessmentId).doesNotContain(assessment);
    assertThatThrownBy(() -> service.openReviewSession(assessment)).isInstanceOf(BusinessException.class);
    assertThat(new ResumeRepository(jdbc).findEvidence(submission)).isEmpty();
  }

  static long[] resumeFixture() {
    long consent=jdbc.queryForObject("INSERT INTO hr_candidate_consents (consent_reference,candidate_reference,purpose,purpose_code,granted_at,expires_at,recorded_by) VALUES (gen_random_uuid()::text,'fictional-candidate','ASSESSMENT','ASSESSMENT',now()-interval '1 hour',now()+interval '1 day','audit-operator') RETURNING id",Long.class);
    long job=jdbc.queryForObject("INSERT INTO resume_jobs (title,sanitized_jd,criteria_json,status,owner_actor_id,logical_job_id,criteria_version,effective_from) VALUES ('fictional role','fictional JD','[]','CRITERIA_CONFIRMED','audit-operator',gen_random_uuid(),1,now()) RETURNING id",Long.class);
    long submission=jdbc.queryForObject("INSERT INTO resume_submissions (job_id,anonymous_candidate_id,sanitized_resume,content_hash,expires_at,consent_id,candidate_reference,source_file_name,source_content_type) VALUES (?,'fictional-candidate','fictional evidence','fixture-hash',now()+interval '1 day',?,'fictional-candidate','fixture.txt','text/plain') RETURNING id",Long.class,job,consent);
    jdbc.update("INSERT INTO resume_evidence (submission_id,evidence_ref,section_name,sanitized_text,position_index) VALUES (?,'E1','experience','fictional evidence',0)",submission);
    long assessment=jdbc.queryForObject("INSERT INTO resume_assessments (job_id,submission_id,content_json,status,owner_actor_id,review_queue,expires_at,criteria_version,original_content_json) VALUES (?,?,'{}','DRAFTED','audit-operator',true,now()+interval '10 minutes',1,'{}') RETURNING id",Long.class,job,submission);
    return new long[] { consent, job, submission, assessment };
  }

  @org.junit.jupiter.api.Test
  void consentRevocationDuringModelCallPreventsPublicationWithoutHoldingTransaction() throws Exception {
    long[] fixture = resumeFixture();
    String consentRef = jdbc.queryForObject("SELECT consent_reference FROM hr_candidate_consents WHERE id=?", String.class, fixture[0]);
    var repository = transactional(new ResumeRepository(jdbc));
    var properties = new dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties(true, 10000, 10000, 10, 10, Duration.ofMinutes(10), true);
    var sanitizer = mock(dev.qcoding.businesscopilot.resumecopilot.privacy.ResumePrivacySanitizer.class);
    when(sanitizer.sanitizeResume(anyString())).thenReturn("fictional experience");
    var evidence = new dev.qcoding.businesscopilot.resumecopilot.evidence.ResumeEvidenceService(properties);
    var ai = mock(dev.qcoding.businesscopilot.aicore.AiChatService.class);
    var prompts = mock(dev.qcoding.businesscopilot.aicore.PromptTemplateService.class);
    when(prompts.renderWithMetadata(anyString(), anyString(), anyMap())).thenReturn(new dev.qcoding.businesscopilot.aicore.RenderedPrompt("fixture", null));
    var guardrail = mock(ResumeAssessmentGuardrail.class);
    when(guardrail.validate(any(), any(), any())).thenReturn(new ResumeAssessmentGuardrail.Validation(false, List.of("fixture needs review")));
    var assessmentService = make(ResumeAssessmentService.class, repository, properties, sanitizer, evidence, ai, prompts, guardrail, ACTOR, ACCESS, TOKENS, JSON);
    var hr = transactional(make(HrEnterpriseService.class, jdbc, ACTOR, JSON, assessmentService));
    when(ai.generateJsonWithMetadata(anyString(), anyString(), eq(dev.qcoding.businesscopilot.resumecopilot.ResumeModels.AssessmentContent.class))).thenAnswer(inv -> {
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_submissions WHERE consent_id=?", Integer.class, fixture[0])).isEqualTo(2);
      // A separate connection can commit revocation while the model request is still in flight.
      CompletableFuture.runAsync(() -> hr.revokeConsent(consentRef)).get(5, TimeUnit.SECONDS);
      return new dev.qcoding.businesscopilot.aicore.AiInvocationResult<>(null, null);
    });
    assertThatThrownBy(() -> hr.assessAuthorized(fixture[1], "fictional-candidate", consentRef, "fixture resume"))
        .isInstanceOf(BusinessException.class).hasMessageContaining("授权已撤回或到期");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_assessments WHERE job_id=?", Integer.class, fixture[1])).isEqualTo(1);
    assertThat(repository.findAssessment(fixture[3])).isNull();
  }

  static dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService reportPersistence(ReportLifecycleService lifecycle) {
    var properties = new dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties(true, 31, 50, 4000, 20, 20, 10, Duration.ofMinutes(30), Set.of(ReportType.BUSINESS_WEEKLY), true);
    return transactional(new dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftPersistenceService(
        new dev.qcoding.businesscopilot.reportcopilot.draft.JdbcReportDraftRepository(jdbc, ACTOR, TOKENS, JSON),
        new dev.qcoding.businesscopilot.reportcopilot.audit.ReportAuditService(jdbc), properties, null, lifecycle));
  }

  static dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService.ReportRequestPreview reportPreview() {
    return new dev.qcoding.businesscopilot.reportcopilot.request.ReportRequestPreparationService.ReportRequestPreview(
        ReportType.BUSINESS_WEEKLY, new ReportPeriod(LocalDate.now().minusDays(6), LocalDate.now()), "fixture lease report", List.of());
  }

  static LlmReportOutput reportContent() {
    return new LlmReportOutput("fixture summary", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  @org.junit.jupiter.api.Test
  void recoveredScheduleRejectsOldPublicationAndCompletesNewRunAtomically() {
    long schedule = jdbc.queryForObject("""
        INSERT INTO report_schedules(schedule_key, report_type, title_template, cron_expression, zone_id,
            template_id, template_version, source_config, owner_actor_id, enabled, next_run_at)
        VALUES (gen_random_uuid()::text,'BUSINESS_WEEKLY','fixture','0 0 9 * * MON','UTC',
            'fixture','v1','{"includeSupportMetrics":true}','audit-operator',true,now()-interval '1 minute') RETURNING id
        """, Long.class);
    var lifecycle = transactional(new ReportLifecycleService(jdbc, JSON));
    lifecycle.claimSchedule();
    UUID oldToken = jdbc.queryForObject("SELECT claim_token FROM report_schedules WHERE id=?", UUID.class, schedule);
    long oldRun = jdbc.queryForObject("SELECT id FROM report_schedule_runs WHERE schedule_id=? AND status='RUNNING'", Long.class, schedule);
    var stale = new ReportPublicationClaim("audit-operator", null, List.of(), schedule, oldToken, oldRun);
    jdbc.update("UPDATE report_schedules SET claimed_at=now()-interval '16 minutes' WHERE id=?", schedule);
    lifecycle.expireScheduleLeases();
    lifecycle.claimSchedule();
    UUID newToken = jdbc.queryForObject("SELECT claim_token FROM report_schedules WHERE id=?", UUID.class, schedule);
    long newRun = jdbc.queryForObject("SELECT id FROM report_schedule_runs WHERE schedule_id=? AND status='RUNNING'", Long.class, schedule);
    long before = jdbc.queryForObject("SELECT count(*) FROM report_drafts", Long.class);
    var persistence = reportPersistence(lifecycle);
    assertThatThrownBy(() -> persistence.createDraft(reportPreview(), reportContent(), "fixture", null, null, "fixture", 1L, stale))
        .isInstanceOf(BusinessException.class);
    lifecycle.failSchedule(stale, "late failure");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM report_drafts", Long.class)).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT reason FROM report_schedule_runs WHERE id=?", String.class, oldRun)).isEqualTo("SCHEDULE_LEASE_EXPIRED");
    assertThat(jdbc.queryForObject("SELECT status FROM report_schedule_runs WHERE id=?", String.class, newRun)).isEqualTo("RUNNING");
    var current = new ReportPublicationClaim("audit-operator", null, List.of(), schedule, newToken, newRun);
    var draft = persistence.createDraft(reportPreview(), reportContent(), "fixture", null, null, "fixture", 1L, current);
    assertThat(jdbc.queryForObject("SELECT report_draft_id FROM report_schedule_runs WHERE id=? AND status='DRAFTED'", Long.class, newRun)).isEqualTo(draft.id());
    assertThat(jdbc.queryForObject("SELECT claim_token IS NULL AND next_run_at > now() FROM report_schedules WHERE id=?", Boolean.class, schedule)).isTrue();
  }

  @org.junit.jupiter.api.Test
  void editingScheduleOrDisablingSourceInvalidatesAnInFlightPublisher() throws Exception {
    var lifecycle = transactional(new ReportLifecycleService(jdbc, JSON));
    var enterprise = transactional(make(ReportEnterpriseService.class, jdbc, JSON, ACTOR, lifecycle));
    for (boolean disableSource : List.of(false, true)) {
      String key = "source-" + UUID.randomUUID();
      var connection = enterprise.saveConnection(new ReportEnterpriseService.ConnectionCommand(
          key, "Fictional notes", ReportEnterpriseService.Provider.MEETING_NOTES, "https://fixture.invalid", null, true));
      var selection = new ReportEnterpriseService.SourceSelection(List.of(connection.id()), List.of(), false, null);
      var command = new ReportEnterpriseService.ScheduleCommand("schedule-" + UUID.randomUUID(), ReportType.BUSINESS_WEEKLY,
          "fixture", "0 0 9 * * MON", "UTC", "fixture", "v1", selection, true);
      var schedule = enterprise.saveSchedule(command);
      jdbc.update("UPDATE report_schedules SET next_run_at=now()-interval '1 day' WHERE id=?", schedule.id());
      lifecycle.claimSchedule();
      UUID claimToken = jdbc.queryForObject("SELECT claim_token FROM report_schedules WHERE id=?", UUID.class, schedule.id());
      Long runId = jdbc.queryForObject("SELECT id FROM report_schedule_runs WHERE schedule_id=? AND status='RUNNING'", Long.class, schedule.id());
      assertThat(claimToken).isNotNull();
      var proof = new ReportPublicationClaim("audit-operator", null, List.of(), schedule.id(), claimToken, runId);
      if (disableSource) {
        enterprise.saveConnection(new ReportEnterpriseService.ConnectionCommand(
            key, "Fictional notes", ReportEnterpriseService.Provider.MEETING_NOTES, "https://fixture.invalid", null, false));
      } else { enterprise.saveSchedule(command); }
      assertThatThrownBy(() -> reportPersistence(lifecycle).createDraft(reportPreview(), reportContent(), "fixture", null, null, "fixture", 1L, proof))
          .isInstanceOf(BusinessException.class);
      lifecycle.failSchedule(proof, "late-worker-failure");
      assertThat(jdbc.queryForObject("SELECT reason FROM report_schedule_runs WHERE id=?", String.class, runId))
          .isEqualTo(disableSource ? "SOURCE_DISABLED" : "SCHEDULE_CHANGED");
    }
  }

  @org.junit.jupiter.api.Test
  void lostHandoffLeasePreventsDraftAndTracePublication() {
    var persistence = reportPersistence(transactional(new ReportLifecycleService(jdbc, JSON)));
    long before = jdbc.queryForObject("SELECT count(*) FROM report_drafts", Long.class);
    var claim = new ReportPublicationClaim("audit-operator", UUID.randomUUID(), List.of("expired-handoff"), null, null, null);
    assertThatThrownBy(() -> persistence.createDraft(reportPreview(), reportContent(), "fixture", null, null, "fixture", 1L, claim)).isInstanceOf(BusinessException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM report_drafts", Long.class)).isEqualTo(before);
  }
  @org.junit.jupiter.api.Test
  void metricHandoffFreezesVerifiedValueAndRejectsTruncatedOrStaleDefinitions() {
    var store = new JdbcSqlCandidateStore(jdbc);
    var confirmation = transactional(new SqlConfirmationService(store, new DataCopilotConfirmationProperties(10), ACTOR, ACCESS, TOKENS));
    var candidate = confirmation.createExecutableCandidate("select 120 as revenue limit 1", "metric-scope", "fixture", "fixture");
    String key = "revenue-" + UUID.randomUUID();
    jdbc.update("""
        INSERT INTO data_metric_definitions(metric_key,display_name,description,unit,expression_sql,owner_actor_id,approved_by,active)
        VALUES (?,'Revenue','Paid order revenue','CNY','select 120 as revenue limit 1','author','reviewer',true)
        """, key);
    new SqlCandidateMetricReferenceService(jdbc).record(candidate.candidateId(), List.of(new SqlCandidateMetricReferenceService.MetricReference(key, 1)));
    var results = transactional(new dev.qcoding.businesscopilot.datacopilot.enterprise.DataQueryResultService(jdbc, JSON, ACTOR, Duration.ofDays(1)));
    long result = jdbc.queryForObject("""
        INSERT INTO data_query_results(candidate_id,owner_actor_id,columns_json,rows_json,row_count,truncated,expires_at)
        VALUES (?,'audit-operator','[]','[{"revenue":120}]',1,false,now()+interval '1 day') RETURNING id
        """, Long.class, candidate.candidateId());
    var scope = new dev.qcoding.businesscopilot.datacopilot.enterprise.DataQueryResultService.MetricScope(
        key, "revenue", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-07"), "Asia/Shanghai", true);
    var handoff = results.createReportHandoff(result, "Revenue week", scope);
    var snapshot = JSON.readTree(jdbc.queryForObject("SELECT metric_snapshot::text FROM data_report_handoffs WHERE id=?", String.class, handoff.id()));
    assertThat(snapshot.path("value").asText()).isEqualTo("120");
    assertThat(snapshot.path("confirmedBy").asText()).isEqualTo("audit-operator");
    assertThat(snapshot.path("periodStart").asText()).isEqualTo("2026-09-01");
    assertThat(results.handoffOptions(result).metrics()).hasSize(1);
    jdbc.update("UPDATE data_query_results SET truncated=true WHERE id=?", result);
    assertThatThrownBy(() -> results.createReportHandoff(result, "truncated", scope)).isInstanceOf(BusinessException.class);
    jdbc.update("UPDATE data_query_results SET truncated=false WHERE id=?", result);
    jdbc.update("UPDATE data_metric_definitions SET version=2 WHERE metric_key=?", key);
    assertThatThrownBy(() -> results.createReportHandoff(result, "stale", scope)).isInstanceOf(BusinessException.class);
    assertThat(results.handoffOptions(result).metrics()).isEmpty();
    assertThat(JSON.readTree(jdbc.queryForObject("SELECT metric_snapshot::text FROM data_report_handoffs WHERE id=?", String.class, handoff.id()))
        .path("metricVersion").asText()).isEqualTo("1");

    // Draft publication and source consumption roll back together even after insertion has run.
    UUID token = UUID.randomUUID();
    jdbc.update("UPDATE data_report_handoffs SET status='CLAIMED',claim_token=?,claimed_at=now() WHERE id=?", token, handoff.id());
    String previousRef = "previous-" + UUID.randomUUID();
    jdbc.update("""
        INSERT INTO data_report_handoffs(query_result_id,owner_actor_id,title,source_reference,status,metric_snapshot)
        SELECT query_result_id,owner_actor_id,'Previous period',?,'CONSUMED',metric_snapshot FROM data_report_handoffs WHERE id=?
        """, previousRef, handoff.id());
    var claim = new ReportPublicationClaim("audit-operator", token, List.of(handoff.sourceReference()), null, null, null, previousRef);
    var lifecycle = transactional(new ReportLifecycleService(jdbc, JSON));
    var transaction = new org.springframework.transaction.support.TransactionTemplate(new JdbcTransactionManager(ds));
    long before = jdbc.queryForObject("SELECT count(*) FROM report_drafts", Long.class);
    assertThatThrownBy(() -> transaction.execute(status -> {
      reportPersistence(lifecycle).createDraft(reportPreview(), reportContent(), "fixture", null, null, "fixture", 1L, claim);
      throw new IllegalStateException("injected commit-path failure");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM report_drafts", Long.class)).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT status FROM data_report_handoffs WHERE id=?", String.class, handoff.id())).isEqualTo("CLAIMED");
    var draft = reportPersistence(lifecycle).createDraft(reportPreview(), reportContent(), "fixture", null, null, "fixture", 1L, claim);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM report_draft_data_links WHERE draft_id=? AND source_reference=?", Integer.class,
        draft.id(), handoff.sourceReference())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM data_report_handoffs WHERE id=?", String.class, handoff.id())).isEqualTo("CONSUMED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM report_draft_data_links WHERE draft_id=?", Integer.class, draft.id())).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT status FROM data_report_handoffs WHERE source_reference=?", String.class, previousRef)).isEqualTo("CONSUMED");
  }

  @org.junit.jupiter.api.Test
  void supportPeriodUsesBusinessTimezoneAndDeduplicatedRetainedEventsAcrossDst() throws Exception {
    // New York DST starts March 8: this business day has 23 hours, not 24.
    Instant start = Instant.parse("2026-03-08T05:00:00Z"), end = Instant.parse("2026-03-09T04:00:00Z");
    List<Long> tickets = new ArrayList<>();
    for (Instant created : List.of(start.minusSeconds(1), start, end.minusSeconds(1), end)) {
      tickets.add(jdbc.queryForObject("""
          INSERT INTO support_tickets(customer_message,channel,category,sentiment,urgency,status,owner_actor_id,created_at)
          VALUES ('DST fixture','fixture','OTHER','NEUTRAL','MEDIUM','CLOSED','audit-operator',?) RETURNING id
          """, Long.class, java.sql.Timestamp.from(created)));
    }
    for (Instant event : List.of(start, start.plusSeconds(60), end)) {
      jdbc.update("""
          INSERT INTO support_audit_logs(ticket_id,event_type,created_at)
          VALUES (?,'CUSTOMER_REPLY_RECORDED',?)
          """, tickets.getFirst(), java.sql.Timestamp.from(event));
    }
    var generator = mock(ReportGenerationService.class);
    AtomicReference<ReportGenerateRequest> captured = new AtomicReference<>();
    when(generator.generate(any())).thenAnswer(inv -> {
      ReportGenerateRequest req=inv.getArgument(0); captured.set(req);
      return new ReportDraftResponse(null,req.reportType(),req.period(),req.title(),"REJECTED",null,List.of(),null,null,"fixture",null);
    });
    make(ReportEnterpriseService.class,jdbc,generator,ACTOR,JSON).generate(new ReportEnterpriseService.GenerateCommand(
        ReportType.BUSINESS_WEEKLY, new ReportPeriod(LocalDate.parse("2026-03-08"),LocalDate.parse("2026-03-08"),"America/New_York"),
        "DST fixture",new ReportEnterpriseService.SourceSelection(List.of(),List.of(),true,null),"fixture","v1"));
    var metrics = captured.get().importedSources().stream().collect(java.util.stream.Collectors.toMap(x -> x.attributes().get("name"), x -> x.attributes()));
    assertThat(metrics.get("support.total")).containsEntry("value", "2").containsEntry("periodStart", start.toString()).containsEntry("periodEndExclusive", end.toString());
    assertThat(metrics.get("support.closed")).containsEntry("value", "1").containsEntry("completeness", "RECORDED_EVENTS_ONLY");
    assertThat(metrics.get("support.backlog")).containsEntry("metricType", "SNAPSHOT");
  }

}
