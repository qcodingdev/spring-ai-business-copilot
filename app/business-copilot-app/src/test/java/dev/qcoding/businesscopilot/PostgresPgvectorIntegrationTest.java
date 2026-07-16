package dev.qcoding.businesscopilot;

import dev.qcoding.businesscopilot.audit.AuditEvent;
import dev.qcoding.businesscopilot.audit.AuditEventType;
import dev.qcoding.businesscopilot.audit.AuditStatus;
import dev.qcoding.businesscopilot.audit.JdbcQueryAuditRepository;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.JdbcKnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeChunkEmbedding;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import dev.qcoding.businesscopilot.datacopilot.schema.DataCopilotSchemaProperties;
import dev.qcoding.businesscopilot.datacopilot.schema.JdbcSchemaMetadataRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
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
        String latestMigration = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class);

        assertThat(extension).isEqualTo("vector");
        assertThat(actorColumns).isEqualTo(5);
        assertThat(httpRequestColumns).isEqualTo(5);
        assertThat(latestMigration).isEqualTo("12");
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

        Long indexedDocumentId = insertDocument(upgradeJdbcTemplate, "Indexed document", "b".repeat(64));
        Long indexedChunkId = insertChunk(upgradeJdbcTemplate, indexedDocumentId, 0, "indexed content");
        new JdbcKnowledgeEmbeddingRepository(upgradeJdbcTemplate).saveAll(List.of(
                new KnowledgeChunkEmbedding(null, indexedChunkId, "integration-model", vector(0, 1.0f), null)));
        Long unindexedDocumentId = insertDocument(upgradeJdbcTemplate, "Unindexed document", "c".repeat(64));

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
                String.class)).isEqualTo("12");
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
                    title, source_type, source_name, category, content_hash, enabled
                ) VALUES (?, 'upload', ?, 'integration-test', ?, TRUE)
                RETURNING id
                """, Long.class, "Vector test", "vector-test.txt", "a".repeat(64));
        Long firstChunkId = insertChunk(documentId, 0, "closest chunk");
        Long secondChunkId = insertChunk(documentId, 1, "distant chunk");

        float[] firstVector = vector(0, 1.0f);
        float[] secondVector = vector(1, 1.0f);
        JdbcKnowledgeEmbeddingRepository repository = new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);
        repository.saveAll(List.of(
                new KnowledgeChunkEmbedding(null, firstChunkId, "integration-model", firstVector, null),
                new KnowledgeChunkEmbedding(null, secondChunkId, "integration-model", secondVector, null)));

        List<KnowledgeEmbeddingRepository.SimilaritySearchResult> results =
                repository.findSimilarChunks(firstVector, 5, 0.5);

        assertThat(results).extracting(KnowledgeEmbeddingRepository.SimilaritySearchResult::chunkId)
                .containsExactly(firstChunkId);
        assertThat(repository.findByChunkId(firstChunkId)).isPresent();
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

    private static Long insertDocument(JdbcTemplate target, String title, String contentHash) {
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
