package dev.qcoding.businesscopilot.knowledgecopilot.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed implementation of {@link KnowledgeChunkRepository}.
 *
 * <p>基于 Spring JDBC 的知识分片持久化。表 knowledge_chunks 由 Flyway V4 迁移创建。
 * 批量写入使用逐条 INSERT + RETURNING，保证每条 chunk 都有数据库生成的主键。</p>
 */
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private static final String INSERT_SQL = """
            INSERT INTO knowledge_chunks (
                document_id, section_title, chunk_index, content, content_preview, token_count, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String DELETE_BY_DOCUMENT_SQL = """
            DELETE FROM knowledge_chunks WHERE document_id = ?
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, document_id, section_title, chunk_index, content, content_preview, token_count, created_at
            FROM knowledge_chunks
            WHERE id = ?
            """;

    private static final String FIND_BY_DOCUMENT_SQL = """
            SELECT id, document_id, section_title, chunk_index, content, content_preview, token_count, created_at
            FROM knowledge_chunks
            WHERE document_id = ?
            ORDER BY chunk_index ASC
            """;

    private static final RowMapper<KnowledgeChunk> ROW_MAPPER = (rs, rowNum) -> new KnowledgeChunk(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getString("section_title"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getString("content_preview"),
            (Integer) rs.getObject("token_count"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveAll(List<KnowledgeChunk> chunks) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        for (KnowledgeChunk chunk : chunks) {
            jdbcTemplate.queryForObject(INSERT_SQL, Long.class,
                    chunk.documentId(),
                    chunk.sectionTitle(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    chunk.contentPreview(),
                    chunk.tokenCount(),
                    now);
        }
    }

    @Override
    public int deleteByDocumentId(Long documentId) {
        return jdbcTemplate.update(DELETE_BY_DOCUMENT_SQL, documentId);
    }

    @Override
    public List<KnowledgeChunk> findByDocumentId(Long documentId) {
        return jdbcTemplate.query(FIND_BY_DOCUMENT_SQL, ROW_MAPPER, documentId);
    }

    @Override
    public Optional<KnowledgeChunk> findById(Long id) {
        List<KnowledgeChunk> result = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}
