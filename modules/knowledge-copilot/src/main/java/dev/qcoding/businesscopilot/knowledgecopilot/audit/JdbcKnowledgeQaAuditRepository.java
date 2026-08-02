package dev.qcoding.businesscopilot.knowledgecopilot.audit;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * JDBC-backed implementation of {@link KnowledgeQaAuditRepository}.
 *
 * <p>基于 Spring JDBC 的知识问答审计日志持久化。
 * 表 knowledge_qa_audit_logs 由 Flyway V4 迁移创建。</p>
 *
 * <p>安全说明：审计日志只记录元信息（问题、chunk IDs、状态、耗时、模型），
 * 不记录完整 chunk 内容和敏感字段明文。</p>
 */
public class JdbcKnowledgeQaAuditRepository implements KnowledgeQaAuditRepository {

    private static final String INSERT_SQL = """
            INSERT INTO knowledge_qa_audit_logs (
                request_id, http_request_id, actor_id, question, retrieved_chunk_ids, cited_chunk_ids,
                answer_status, refusal_reason, model_name, embedding_model,
                latency_ms, creator_actor_id, action_actor_id, provider_name,
                provider_request_id, prompt_name, prompt_version, prompt_hash,
                policy_version, violation_codes, input_tokens, output_tokens,
                finish_reason, locale, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_RECENT_SQL = """
            SELECT id, request_id, question, retrieved_chunk_ids, cited_chunk_ids,
                   answer_status, refusal_reason, model_name, embedding_model,
                   latency_ms, creator_actor_id, action_actor_id, provider_name,
                   provider_request_id, prompt_name, prompt_version, prompt_hash,
                   policy_version, violation_codes, input_tokens, output_tokens,
                   finish_reason, anonymized_at, created_at
            FROM knowledge_qa_audit_logs
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM knowledge_qa_audit_logs";

    private static final RowMapper<KnowledgeQaAuditLog> ROW_MAPPER = (rs, rowNum) -> new KnowledgeQaAuditLog(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("question"),
            rs.getString("retrieved_chunk_ids"),
            rs.getString("cited_chunk_ids"),
            rs.getString("answer_status"),
            rs.getString("refusal_reason"),
            rs.getString("model_name"),
            rs.getString("embedding_model"),
            (Long) rs.getObject("latency_ms"),
            rs.getString("creator_actor_id"),
            rs.getString("action_actor_id"),
            rs.getString("provider_name"),
            rs.getString("provider_request_id"),
            rs.getString("prompt_name"),
            rs.getString("prompt_version"),
            rs.getString("prompt_hash"),
            rs.getString("policy_version"),
            rs.getString("violation_codes"),
            (Integer) rs.getObject("input_tokens"),
            (Integer) rs.getObject("output_tokens"),
            rs.getString("finish_reason"),
            rs.getTimestamp("anonymized_at") != null
                    ? rs.getTimestamp("anonymized_at").toInstant() : null,
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null);

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeQaAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Long save(KnowledgeQaAuditLog log) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        return jdbcTemplate.queryForObject(INSERT_SQL, Long.class,
                log.requestId(),
                BusinessRequestContextHolder.currentRequestId(),
                BusinessRequestContextHolder.currentActorId(),
                log.question(),
                log.retrievedChunkIds(),
                log.citedChunkIds(),
                log.answerStatus(),
                log.refusalReason(),
                log.modelName(),
                log.embeddingModel(),
                log.latencyMs(),
                log.creatorActorId() != null ? log.creatorActorId()
                        : BusinessRequestContextHolder.currentActorId(),
                log.actionActorId(),
                log.providerName(),
                log.providerRequestId(),
                log.promptName(),
                log.promptVersion(),
                log.promptHash(),
                log.policyVersion(),
                log.violationCodes(),
                log.inputTokens(),
                log.outputTokens(),
                log.finishReason(),
                BusinessRequestContextHolder.currentLocale(),
                now);
    }

    @Override
    public List<KnowledgeQaAuditLog> findRecent(int page, int size) {
        int offset = Math.max(0, page) * size;
        return jdbcTemplate.query(FIND_RECENT_SQL, ROW_MAPPER, size, offset);
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        return count != null ? count : 0L;
    }
}
