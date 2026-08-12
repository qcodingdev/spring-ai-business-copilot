package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL 实现：反馈写入同时校验问答归属，避免跨操作者绑定。 */
public class JdbcKnowledgeFeedbackRepository implements KnowledgeFeedbackRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO knowledge_answer_feedback (
                audit_log_id, actor_id, rating, reason, comment
            )
            SELECT audit.id, ?, ?, ?, ?
            FROM knowledge_qa_audit_logs audit
            WHERE audit.id = ?
              AND COALESCE(audit.creator_actor_id, audit.actor_id) = ?
            ON CONFLICT (audit_log_id, actor_id)
            DO UPDATE SET
                rating = EXCLUDED.rating,
                reason = EXCLUDED.reason,
                comment = EXCLUDED.comment,
                revision = knowledge_answer_feedback.revision + 1,
                updated_at = now()
            RETURNING id, audit_log_id, actor_id, rating, reason, comment, created_at, updated_at
            """;

    private static final String QUALITY_ISSUES_CTE = """
            WITH quality_issues AS (
                SELECT audit.id AS answer_id,
                       audit.request_id,
                       audit.question,
                       audit.answer_preview,
                       audit.retrieved_chunk_ids,
                       audit.cited_chunk_ids,
                       audit.answer_status,
                       audit.refusal_reason,
                       feedback.rating,
                       feedback.reason,
                       feedback.comment,
                       audit.created_at AS answer_created_at,
                       feedback.updated_at AS feedback_updated_at,
                       COALESCE(feedback.revision, 0) AS issue_version,
                       GREATEST(
                           audit.created_at,
                           COALESCE(feedback.updated_at, audit.created_at)
                       ) AS issue_updated_at
                FROM knowledge_qa_audit_logs audit
                LEFT JOIN knowledge_answer_feedback feedback
                  ON feedback.audit_log_id = audit.id
                WHERE audit.answer_status <> 'ANSWERED'
                   OR feedback.rating = 'NOT_HELPFUL'
            )
            """;

    private static final String QUALITY_QUEUE_SQL = QUALITY_ISSUES_CTE + """
            SELECT issue.*
            FROM quality_issues issue
            LEFT JOIN knowledge_quality_reviews review
              ON review.audit_log_id = issue.answer_id
            WHERE review.id IS NULL
               OR review.reviewed_issue_version < issue.issue_version
            ORDER BY issue.issue_updated_at DESC, issue.answer_id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String QUALITY_QUEUE_COUNT_SQL = QUALITY_ISSUES_CTE + """
            SELECT COUNT(*)
            FROM quality_issues issue
            LEFT JOIN knowledge_quality_reviews review
              ON review.audit_log_id = issue.answer_id
            WHERE review.id IS NULL
               OR review.reviewed_issue_version < issue.issue_version
            """;

    private static final String REVIEW_SQL = QUALITY_ISSUES_CTE + """
            INSERT INTO knowledge_quality_reviews (
                audit_log_id,
                decision,
                evidence_assessment,
                answer_assessment,
                remediation_action,
                review_note,
                reviewer_actor_id,
                reviewed_issue_version,
                reviewed_issue_at
            )
            SELECT issue.answer_id, ?, ?, ?, ?, ?, ?, issue.issue_version, issue.issue_updated_at
            FROM quality_issues issue
            WHERE issue.answer_id = ?
              AND issue.issue_version = ?
              AND issue.issue_updated_at = ?
            ON CONFLICT (audit_log_id)
            DO UPDATE SET
                decision = EXCLUDED.decision,
                evidence_assessment = EXCLUDED.evidence_assessment,
                answer_assessment = EXCLUDED.answer_assessment,
                remediation_action = EXCLUDED.remediation_action,
                review_note = EXCLUDED.review_note,
                reviewer_actor_id = EXCLUDED.reviewer_actor_id,
                reviewed_issue_version = EXCLUDED.reviewed_issue_version,
                reviewed_issue_at = EXCLUDED.reviewed_issue_at,
                updated_at = now()
            WHERE knowledge_quality_reviews.reviewed_issue_version
                  < EXCLUDED.reviewed_issue_version
            RETURNING id, audit_log_id, decision, evidence_assessment, answer_assessment,
                      remediation_action, review_note, reviewer_actor_id,
                      reviewed_issue_version, reviewed_issue_at, created_at, updated_at
            """;

    private static final String QUALITY_METRICS_SQL = QUALITY_ISSUES_CTE + """
            SELECT
                (SELECT COUNT(*) FROM knowledge_answer_feedback) AS feedback_count,
                (SELECT COUNT(*) FROM knowledge_answer_feedback
                    WHERE rating = 'HELPFUL') AS helpful_count,
                (SELECT COUNT(*) FROM knowledge_answer_feedback
                    WHERE rating = 'NOT_HELPFUL') AS not_helpful_count,
                (SELECT COUNT(*)
                   FROM quality_issues issue
                   LEFT JOIN knowledge_quality_reviews review
                     ON review.audit_log_id = issue.answer_id
                  WHERE review.id IS NULL
                     OR review.reviewed_issue_version < issue.issue_version) AS pending_review_count,
                (SELECT COUNT(*) FROM knowledge_quality_reviews
                    WHERE decision = 'RESOLVED') AS resolved_count,
                (SELECT COUNT(*) FROM knowledge_quality_reviews
                    WHERE decision = 'DISMISSED') AS dismissed_count,
                (SELECT COUNT(*) FROM knowledge_quality_reviews
                    WHERE decision = 'KNOWLEDGE_UPDATE_REQUIRED') AS knowledge_update_required_count
            """;

    private static final RowMapper<KnowledgeAnswerFeedback> FEEDBACK_ROW_MAPPER =
            (rs, rowNum) -> new KnowledgeAnswerFeedback(
                    rs.getLong("id"),
                    rs.getLong("audit_log_id"),
                    rs.getString("actor_id"),
                    KnowledgeFeedbackRating.valueOf(rs.getString("rating")),
                    rs.getString("reason") == null
                            ? null : KnowledgeFeedbackReason.valueOf(rs.getString("reason")),
                    rs.getString("comment"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<KnowledgeQualityQueueItem> QUALITY_ROW_MAPPER =
            (rs, rowNum) -> new KnowledgeQualityQueueItem(
                    rs.getLong("answer_id"),
                    rs.getString("request_id"),
                    rs.getString("question"),
                    rs.getString("answer_preview"),
                    rs.getString("retrieved_chunk_ids"),
                    rs.getString("cited_chunk_ids"),
                    rs.getString("answer_status"),
                    rs.getString("refusal_reason"),
                    rs.getString("rating") == null
                            ? null : KnowledgeFeedbackRating.valueOf(rs.getString("rating")),
                    rs.getString("reason") == null
                            ? null : KnowledgeFeedbackReason.valueOf(rs.getString("reason")),
                    rs.getString("comment"),
                    rs.getTimestamp("answer_created_at").toInstant(),
                    rs.getTimestamp("feedback_updated_at") == null
                            ? null : rs.getTimestamp("feedback_updated_at").toInstant(),
                    rs.getLong("issue_version"),
                    rs.getTimestamp("issue_updated_at").toInstant());

    private static final RowMapper<KnowledgeQualityReview> REVIEW_ROW_MAPPER =
            (rs, rowNum) -> new KnowledgeQualityReview(
                    rs.getLong("id"),
                    rs.getLong("audit_log_id"),
                    KnowledgeQualityReviewDecision.valueOf(rs.getString("decision")),
                    KnowledgeEvidenceAssessment.valueOf(rs.getString("evidence_assessment")),
                    KnowledgeAnswerAssessment.valueOf(rs.getString("answer_assessment")),
                    KnowledgeRemediationAction.valueOf(rs.getString("remediation_action")),
                    rs.getString("review_note"),
                    rs.getString("reviewer_actor_id"),
                    rs.getLong("reviewed_issue_version"),
                    rs.getTimestamp("reviewed_issue_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<KnowledgeAnswerFeedback> upsert(
            Long answerId,
            String actorId,
            KnowledgeFeedbackRating rating,
            KnowledgeFeedbackReason reason,
            String comment) {
        List<KnowledgeAnswerFeedback> saved = jdbcTemplate.query(
                UPSERT_SQL,
                FEEDBACK_ROW_MAPPER,
                actorId,
                rating.name(),
                reason == null ? null : reason.name(),
                comment,
                answerId,
                actorId);
        return saved.stream().findFirst();
    }

    @Override
    public List<KnowledgeQualityQueueItem> findQualityQueue(int page, int size) {
        int offset = Math.max(page, 0) * size;
        return jdbcTemplate.query(QUALITY_QUEUE_SQL, QUALITY_ROW_MAPPER, size, offset);
    }

    @Override
    public long countQualityQueue() {
        Long count = jdbcTemplate.queryForObject(QUALITY_QUEUE_COUNT_SQL, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<KnowledgeQualityReview> review(
            Long answerId,
            KnowledgeQualityReviewDecision decision,
            KnowledgeEvidenceAssessment evidenceAssessment,
            KnowledgeAnswerAssessment answerAssessment,
            KnowledgeRemediationAction remediationAction,
            String reviewNote,
            String reviewerActorId,
            long expectedIssueVersion,
            Instant expectedIssueUpdatedAt) {
        List<KnowledgeQualityReview> saved = jdbcTemplate.query(
                REVIEW_SQL,
                REVIEW_ROW_MAPPER,
                decision.name(),
                evidenceAssessment.name(),
                answerAssessment.name(),
                remediationAction.name(),
                reviewNote,
                reviewerActorId,
                answerId,
                expectedIssueVersion,
                Timestamp.from(expectedIssueUpdatedAt));
        return saved.stream().findFirst();
    }

    @Override
    public KnowledgeQualityMetrics qualityMetrics() {
        return jdbcTemplate.queryForObject(
                QUALITY_METRICS_SQL,
                (rs, rowNum) -> new KnowledgeQualityMetrics(
                        rs.getLong("feedback_count"),
                        rs.getLong("helpful_count"),
                        rs.getLong("not_helpful_count"),
                        rs.getLong("pending_review_count"),
                        rs.getLong("resolved_count"),
                        rs.getLong("dismissed_count"),
                        rs.getLong("knowledge_update_required_count")));
    }
}
