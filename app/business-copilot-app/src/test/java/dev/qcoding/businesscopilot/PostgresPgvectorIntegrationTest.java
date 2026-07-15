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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
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
        assertThat(latestMigration).isEqualTo("9");
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
        return jdbcTemplate.queryForObject("""
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
