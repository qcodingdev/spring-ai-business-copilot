package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed implementation of {@link KnowledgeEmbeddingRepository}.
 *
 * <p>基于 Spring JDBC 的向量嵌入持久化。表 knowledge_chunk_embeddings
 * 由 Flyway V4 迁移创建。pgvector 列使用 {@code ::vector} 类型转换字符串
 * 写入，读取时将 pgvector 的字符串表示解析回 {@code float[]}。</p>
 */
public class JdbcKnowledgeEmbeddingRepository implements KnowledgeEmbeddingRepository {

    private static final String INSERT_SQL = """
            INSERT INTO knowledge_chunk_embeddings (chunk_id, embedding_model, embedding, created_at)
            VALUES (?, ?, ?::vector, ?)
            """;

    private static final String DELETE_BY_CHUNK_SQL = """
            DELETE FROM knowledge_chunk_embeddings WHERE chunk_id = ?
            """;

    private static final String FIND_BY_CHUNK_SQL = """
            SELECT id, chunk_id, embedding_model, embedding, created_at
            FROM knowledge_chunk_embeddings
            WHERE chunk_id = ?
            """;

    private static final String DELETE_BY_DOCUMENT_SQL = """
            DELETE FROM knowledge_chunk_embeddings
            WHERE chunk_id IN (SELECT id FROM knowledge_chunks WHERE document_id = ?)
            """;

    /**
     * Cosine similarity search using pgvector's {@code <=>} operator.
     * 1 - (embedding <=> query) converts cosine distance to cosine similarity.
     * The JOIN with knowledge_chunks and knowledge_documents ensures we only return
     * chunks from enabled documents.
     *
     * <p>Uses a CTE to reference the query vector once, then computes similarity in the
     * outer SELECT so we can also filter on it in WHERE without the planner
     * recomputing the distance. The WHERE clause filters to enabled documents only.</p>
     */
    private static final String SIMILARITY_SEARCH_SQL = """
            WITH similarity AS (
                SELECT e.chunk_id,
                       1 - (e.embedding <=> ?::vector) AS similarity
                FROM knowledge_chunk_embeddings e
            )
            SELECT s.chunk_id, s.similarity
            FROM similarity s
            JOIN knowledge_chunks c    ON c.id = s.chunk_id
            JOIN knowledge_documents d ON d.id = c.document_id
            WHERE d.enabled = TRUE
              AND s.similarity >= ?
            ORDER BY s.similarity DESC
            LIMIT ?
            """;

    private static final RowMapper<KnowledgeEmbeddingRepository.SimilaritySearchResult> SIMILARITY_ROW_MAPPER =
            (rs, rowNum) -> new KnowledgeEmbeddingRepository.SimilaritySearchResult(
                    rs.getLong("chunk_id"),
                    rs.getDouble("similarity"));

    private static final RowMapper<KnowledgeChunkEmbedding> ROW_MAPPER = (rs, rowNum) -> {
        float[] embedding = readVectorColumn(rs);
        return new KnowledgeChunkEmbedding(
                rs.getLong("id"),
                rs.getLong("chunk_id"),
                rs.getString("embedding_model"),
                embedding,
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveAll(List<KnowledgeChunkEmbedding> embeddings) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        for (KnowledgeChunkEmbedding emb : embeddings) {
            jdbcTemplate.update(INSERT_SQL,
                    emb.chunkId(),
                    emb.embeddingModel(),
                    formatVector(emb.embedding()),
                    now);
        }
    }

    @Override
    public int deleteByChunkId(Long chunkId) {
        return jdbcTemplate.update(DELETE_BY_CHUNK_SQL, chunkId);
    }

    @Override
    public Optional<KnowledgeChunkEmbedding> findByChunkId(Long chunkId) {
        List<KnowledgeChunkEmbedding> result = jdbcTemplate.query(FIND_BY_CHUNK_SQL, ROW_MAPPER, chunkId);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public int deleteByDocumentId(Long documentId) {
        return jdbcTemplate.update(DELETE_BY_DOCUMENT_SQL, documentId);
    }

    @Override
    public List<KnowledgeEmbeddingRepository.SimilaritySearchResult> findSimilarChunks(
            float[] embedding, int topK, double minSimilarity) {
        return jdbcTemplate.query(SIMILARITY_SEARCH_SQL, SIMILARITY_ROW_MAPPER,
                formatVector(embedding), topK, minSimilarity);
    }

    /**
     * Read a pgvector column from the result set and parse it into a float array.
     *
     * <p>The PG JDBC driver returns the vector type as its {@code toString()} representation
     * (e.g. {@code [0.1, 0.2, 0.3]}). We parse this string back into {@code float[]}
     * to avoid an explicit compile-time dependency on {@code PGobject}.</p>
     */
    private static float[] readVectorColumn(ResultSet rs) throws SQLException {
        Object obj = rs.getObject("embedding");
        if (obj == null) {
            return new float[0];
        }
        /*
         * At runtime the PostgreSQL JDBC driver (42.7.x) delivers the vector as
         * org.postgresql.util.PGobject with getValue() -> "[v1,v2,...]".
         * Calling toString() on it delegates to getValue(), so .toString() is safe.
         */
        return parseVectorString(obj.toString());
    }

    /** Format a float array as a pgvector-compatible string literal: {@code [0.1,0.2,0.3]}. */
    static String formatVector(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Parse a pgvector string representation back to a float array.
     * Input format from {@code toString()} on a pgvector column: {@code [0.1, 0.2, 0.3]}.
     */
    static float[] parseVectorString(String vectorStr) {
        if (vectorStr == null || vectorStr.length() < 2) {
            return new float[0];
        }
        String inner = vectorStr.substring(1, vectorStr.length() - 1);
        if (inner.isBlank()) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
