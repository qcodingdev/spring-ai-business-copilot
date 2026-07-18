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

    private static final String TEXT_SEARCH_SQL = """
            SELECT c.id AS chunk_id,
                   ts_rank_cd(to_tsvector('simple', c.content), websearch_to_tsquery('simple', ?)) AS rank
            FROM knowledge_chunks c
            JOIN knowledge_documents d ON d.id = c.document_id
            WHERE d.enabled = TRUE
              AND d.current_version = TRUE
              AND d.index_status = 'INDEXED'
              AND to_tsvector('simple', c.content) @@ websearch_to_tsquery('simple', ?)
            ORDER BY rank DESC, c.id
            LIMIT ?
            """;

    private static final String KEYWORD_SEARCH_SQL = """
            WITH query_terms AS (
                SELECT DISTINCT lower(trim(term)) AS term
                FROM unnest(string_to_array(?, E'\\n')) AS term
                WHERE char_length(trim(term)) >= 2
            ),
            term_stats AS (
                SELECT SUM(char_length(term) * char_length(term))::double precision AS total_weight
                FROM query_terms
            )
            SELECT c.id AS chunk_id,
                   SUM(char_length(t.term) * char_length(t.term))::double precision
                       / NULLIF(s.total_weight, 0) AS rank
            FROM knowledge_chunks c
            JOIN knowledge_documents d ON d.id = c.document_id
            CROSS JOIN query_terms t
            CROSS JOIN term_stats s
            WHERE d.enabled = TRUE
              AND d.current_version = TRUE
              AND d.index_status = 'INDEXED'
              AND strpos(lower(coalesce(c.section_title, '') || ' ' || c.content), t.term) > 0
            GROUP BY c.id, s.total_weight
            ORDER BY rank DESC, c.id
            LIMIT ?
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

    @Override
    public List<TextSearchResult> findByTextSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(TEXT_SEARCH_SQL,
                (rs, rowNum) -> new TextSearchResult(
                        rs.getLong("chunk_id"), rs.getDouble("rank")),
                query, query, limit);
    }

    @Override
    public List<TextSearchResult> findByKeywordSearch(List<String> terms, int limit) {
        if (terms == null || terms.isEmpty() || limit <= 0) {
            return List.of();
        }
        String encodedTerms = terms.stream()
                .filter(term -> term != null && !term.isBlank() && !term.contains("\n"))
                .distinct()
                .limit(32)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (encodedTerms.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(KEYWORD_SEARCH_SQL,
                (rs, rowNum) -> new TextSearchResult(
                        rs.getLong("chunk_id"), rs.getDouble("rank")),
                encodedTerms, limit);
    }
}
