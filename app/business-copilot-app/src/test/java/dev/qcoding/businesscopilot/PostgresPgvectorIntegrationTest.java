package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.audit.AuditEvent;
import dev.qcoding.businesscopilot.audit.AuditEventType;
import dev.qcoding.businesscopilot.audit.AuditStatus;
import dev.qcoding.businesscopilot.audit.JdbcQueryAuditRepository;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.knowledgecopilot.document.JdbcKnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.JdbcKnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeChunkEmbedding;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.JdbcKnowledgeIndexJobRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexJobStatus;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeQueryTerms;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.JdbcKnowledgeFeedbackRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackRating;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeFeedbackReason;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeQualityReviewDecision;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeEvidenceAssessment;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeAnswerAssessment;
import dev.qcoding.businesscopilot.knowledgecopilot.feedback.KnowledgeRemediationAction;
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
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
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
        assertThat(latestMigration).isEqualTo("31");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT format_type(atttypid, atttypmod) "
                        + "FROM pg_attribute "
                        + "WHERE attrelid = 'knowledge_chunk_embeddings'::regclass "
                        + "AND attname = 'embedding'",
                String.class)).isEqualTo("vector");
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
                String.class)).isEqualTo("31");
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
                String.class)).isEqualTo("31");
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
                  AND column_name IN ('guidance', 'required', 'owner_role')
                """, Integer.class)).isEqualTo(3);
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
}
