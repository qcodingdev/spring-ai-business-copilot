package dev.qcoding.businesscopilot.supportcopilot.queue;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditLog;
import dev.qcoding.businesscopilot.supportcopilot.audit.SupportAuditService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 面向人工复核的客服队列，只返回脱敏后的工单和草稿摘要。 */
public class SupportQueueService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider actorProvider;
    private final SupportAuditService auditService;

    public SupportQueueService(JdbcTemplate jdbcTemplate, CurrentActorProvider actorProvider) {
        this(jdbcTemplate, actorProvider, null);
    }

    public SupportQueueService(JdbcTemplate jdbcTemplate, CurrentActorProvider actorProvider,
                               SupportAuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    public List<QueueItem> find(
            String status, String category, String urgency, String riskLevel, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                SELECT t.external_id, t.customer_message, t.category, t.sentiment,
                       t.urgency, t.status, t.created_at,
                       d.id AS draft_id, d.status AS draft_status, d.risk_level,
                       d.risk_reasons, d.original_draft_text, d.edited_draft_text,
                       d.edit_reason, d.decision_outcome, d.knowledge_version_ids
                FROM support_tickets t
                LEFT JOIN LATERAL (
                    SELECT *
                    FROM support_reply_drafts candidate
                    WHERE candidate.ticket_id = t.id
                    ORDER BY candidate.created_at DESC, candidate.id DESC
                    LIMIT 1
                ) d ON TRUE
                WHERE (?::text IS NULL OR t.status = ?::text)
                  AND (?::text IS NULL OR t.category = ?::text)
                  AND (?::text IS NULL OR t.urgency = ?::text)
                  AND (?::text IS NULL OR d.risk_level = ?::text)
                  AND (t.system_managed = TRUE OR t.owner_actor_id = ?)
                ORDER BY
                    CASE t.urgency
                        WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2
                        WHEN 'MEDIUM' THEN 3 ELSE 4 END,
                    t.created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new QueueItem(
                rs.getString("external_id"),
                rs.getString("customer_message"),
                rs.getString("category"),
                rs.getString("sentiment"),
                rs.getString("urgency"),
                rs.getString("status"),
                (Long) rs.getObject("draft_id"),
                rs.getString("draft_status"),
                rs.getString("risk_level"),
                lines(rs.getString("risk_reasons")),
                rs.getString("edited_draft_text") != null
                        ? rs.getString("edited_draft_text") : rs.getString("original_draft_text"),
                rs.getString("edit_reason"),
                rs.getString("decision_outcome"),
                lines(rs.getString("knowledge_version_ids")),
                rs.getTimestamp("created_at").toInstant()),
                blankToNull(status), blankToNull(status),
                blankToNull(category), blankToNull(category),
                blankToNull(urgency), blankToNull(urgency),
                blankToNull(riskLevel), blankToNull(riskLevel),
                actorProvider.currentActor().actorId(),
                boundedLimit);
    }

    /**
     * Records that a high-risk ticket without an AI reply draft was handled through the real support channel.
     * This deliberately does not send a message or call any external system.
     */
    public ManualResolutionResult recordManualReply(String externalReference) {
        if (auditService == null) {
            throw new IllegalStateException("Support audit service is required for manual ticket resolution");
        }
        String actorId = actorProvider.currentActor().actorId();
        List<Long> ticketIds = jdbcTemplate.query("""
                SELECT id
                FROM support_tickets
                WHERE external_id = ?
                  AND (system_managed = TRUE OR owner_actor_id = ?)
                """, (rs, rowNum) -> rs.getLong("id"), externalReference, actorId);
        if (ticketIds.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Long ticketId = ticketIds.getFirst();
        int updated = jdbcTemplate.update("""
                UPDATE support_tickets
                SET status = 'CLOSED', updated_at = ?
                WHERE id = ? AND status = 'NEEDS_HUMAN'
                """, java.sql.Timestamp.from(Instant.now()), ticketId);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        auditService.recordRequired(new SupportAuditLog(
                null, UUID.randomUUID().toString(), ticketId, "CUSTOMER_REPLY_RECORDED",
                null, null, null, null, null,
                null, null, actorId, actorId,
                null, null, null, null, null, null,
                null, null, null, null, null, null));
        return new ManualResolutionResult("CLOSED");
    }

    private List<String> lines(String value) {
        if (value == null || value.isBlank()) return List.of();
        return value.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record QueueItem(
            String externalReference,
            String customerQuestion,
            String category,
            String sentiment,
            String urgency,
            String status,
            Long draftId,
            String draftStatus,
            String riskLevel,
            List<String> riskReasons,
            String suggestedReply,
            String editReason,
            String decisionOutcome,
            List<String> knowledgeVersions,
            Instant createdAt) {
    }

    public record ManualResolutionResult(String status) {
    }
}
