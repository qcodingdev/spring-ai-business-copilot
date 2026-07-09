package dev.qcoding.businesscopilot.knowledgecopilot.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed implementation of {@link KnowledgeDocumentRepository}.
 *
 * <p>基于 Spring JDBC 的知识文档持久化。表 knowledge_documents 由 Flyway V4 迁移创建。</p>
 */
public class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private static final String INSERT_SQL = """
            INSERT INTO knowledge_documents (
                title, source_type, source_name, category, content_hash, enabled, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, title, source_type, source_name, category, content_hash, enabled, created_at, updated_at
            FROM knowledge_documents
            WHERE id = ?
            """;

    private static final String FIND_ALL_SQL = """
            SELECT id, title, source_type, source_name, category, content_hash, enabled, created_at, updated_at
            FROM knowledge_documents
            ORDER BY created_at DESC, id DESC
            """;

    private static final String EXISTS_BY_HASH_SQL = """
            SELECT 1 FROM knowledge_documents WHERE content_hash = ? LIMIT 1
            """;

    private static final String UPDATE_ENABLED_SQL = """
            UPDATE knowledge_documents SET enabled = ?, updated_at = ? WHERE id = ?
            """;

    private static final RowMapper<KnowledgeDocument> ROW_MAPPER = (rs, rowNum) -> new KnowledgeDocument(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("source_type"),
            rs.getString("source_name"),
            rs.getString("category"),
            rs.getString("content_hash"),
            rs.getBoolean("enabled"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Long save(KnowledgeDocument document) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        return jdbcTemplate.queryForObject(INSERT_SQL, Long.class,
                document.title(),
                document.sourceType(),
                document.sourceName(),
                document.category(),
                document.contentHash(),
                document.enabled(),
                document.createdAt() != null ? Timestamp.from(document.createdAt()) : now,
                now);
    }

    @Override
    public Optional<KnowledgeDocument> findById(Long id) {
        List<KnowledgeDocument> result = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public List<KnowledgeDocument> findAll() {
        return jdbcTemplate.query(FIND_ALL_SQL, ROW_MAPPER);
    }

    @Override
    public boolean existsByContentHash(String contentHash) {
        List<Integer> result = jdbcTemplate.queryForList(EXISTS_BY_HASH_SQL, Integer.class, contentHash);
        return !result.isEmpty();
    }

    @Override
    public boolean updateEnabled(Long id, boolean enabled) {
        int rows = jdbcTemplate.update(UPDATE_ENABLED_SQL, enabled, Timestamp.from(java.time.Instant.now()), id);
        return rows > 0;
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}
