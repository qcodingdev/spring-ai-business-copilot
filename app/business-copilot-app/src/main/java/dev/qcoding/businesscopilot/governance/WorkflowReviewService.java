package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.IndependentReviewService;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL-backed independent review queue shared by Data and Report. */
@Service
public class WorkflowReviewService implements IndependentReviewService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider actorProvider;

    public WorkflowReviewService(JdbcTemplate jdbcTemplate, CurrentActorProvider actorProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
    }

    @Override
    @Transactional
    public ReviewTask register(SubjectType subjectType, String subjectId, String ownerActorId) {
        CurrentActor actor = requireActor();
        requireOwnerOrAdmin(actor, ownerActorId);
        Status initial = actor.hasRole(BusinessRole.ADMIN) ? Status.APPROVED : Status.PENDING;
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO workflow_review_tasks (
                    subject_type, subject_id, owner_actor_id, status,
                    reviewer_actor_id, review_note, submitted_at, reviewed_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (subject_type, subject_id) DO NOTHING
                """, subjectType.name(), subjectId, ownerActorId, initial.name(),
                initial == Status.APPROVED ? actor.actorId() : null,
                initial == Status.APPROVED ? "ADMIN_AUTO_APPROVAL" : null,
                Timestamp.from(now), initial == Status.APPROVED ? Timestamp.from(now) : null,
                Timestamp.from(now));
        return status(subjectType, subjectId);
    }

    @Override
    @Transactional
    public ReviewTask contentChanged(SubjectType subjectType, String subjectId, String ownerActorId) {
        CurrentActor actor = requireActor();
        requireOwnerOrAdmin(actor, ownerActorId);
        ReviewTask current = requireTask(subjectType, subjectId);
        if (!ownerActorId.equals(current.ownerActorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        Status next = actor.hasRole(BusinessRole.ADMIN) ? Status.APPROVED : Status.PENDING;
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE workflow_review_tasks
                SET status = ?, reviewer_actor_id = ?, review_note = ?,
                    reviewed_at = ?, submitted_at = ?, content_version = content_version + 1,
                    updated_at = ?
                WHERE id = ? AND content_version = ?
                """, next.name(), next == Status.APPROVED ? actor.actorId() : null,
                next == Status.APPROVED ? "ADMIN_AUTO_APPROVAL" : null,
                next == Status.APPROVED ? Timestamp.from(now) : null,
                Timestamp.from(now), Timestamp.from(now), current.id(), current.contentVersion());
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return status(subjectType, subjectId);
    }

    @Override
    @Transactional
    public ReviewTask supersede(SubjectType subjectType, String subjectId, String ownerActorId) {
        CurrentActor actor = requireActor();
        requireOwnerOrAdmin(actor, ownerActorId);
        ReviewTask current = requireTask(subjectType, subjectId);
        if (!ownerActorId.equals(current.ownerActorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        if (current.status() == Status.SUPERSEDED) {
            return withSubject(current);
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE workflow_review_tasks
                SET status = 'SUPERSEDED', reviewer_actor_id = NULL,
                    review_note = NULL, reviewed_at = ?,
                    content_version = content_version + 1, updated_at = ?
                WHERE id = ? AND content_version = ?
                """, Timestamp.from(now), Timestamp.from(now), current.id(), current.contentVersion());
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return status(subjectType, subjectId);
    }

    @Override
    public ReviewTask status(SubjectType subjectType, String subjectId) {
        ReviewTask task = requireTask(subjectType, subjectId);
        CurrentActor actor = requireActor();
        if (!actor.hasRole(BusinessRole.ADMIN)
                && !actor.hasRole(BusinessRole.REVIEWER)
                && !actor.actorId().equals(task.ownerActorId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return withSubject(task);
    }

    @Override
    public void requireApproved(SubjectType subjectType, String subjectId, String ownerActorId) {
        ReviewTask task = requireTask(subjectType, subjectId);
        CurrentActor actor = requireActor();
        // Administrators are the explicit exception to the four-eyes rule: they
        // may recover/continue an object even when no independent reviewer is
        // available.  Normal operators still need an APPROVED task owned by
        // themselves, preventing self-review and accidental cross-user use.
        if (!actor.hasRole(BusinessRole.ADMIN)
                && (!ownerActorId.equals(task.ownerActorId()) || task.status() != Status.APPROVED)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该业务对象尚未完成独立复核，不能继续执行。请等待复核员处理。");
        }
    }

    @Override
    public List<ReviewTask> queue(SubjectType subjectType) {
        CurrentActor actor = requireReviewer();
        String sql = subjectType == SubjectType.REPORT_DRAFT ? """
                SELECT review.* FROM workflow_review_tasks review
                JOIN report_drafts draft ON draft.id::text = review.subject_id
                WHERE review.status = 'PENDING' AND review.subject_type = ?
                  AND draft.status IN ('DRAFTED', 'NEEDS_REVIEW')
                  AND (? OR review.owner_actor_id <> ?)
                ORDER BY review.submitted_at, review.id LIMIT 100
                """ : """
                SELECT * FROM workflow_review_tasks
                WHERE status = 'PENDING' AND subject_type = ?
                  AND (? OR owner_actor_id <> ?)
                ORDER BY submitted_at, id LIMIT 100
                """;
        return jdbcTemplate.query(sql, mapper(), subjectType.name(),
                        actor.hasRole(BusinessRole.ADMIN), actor.actorId())
                .stream().map(this::withSubject).toList();
    }

    @Override
    public List<ReviewTask> mine(SubjectType subjectType) {
        CurrentActor actor = requireActor();
        return jdbcTemplate.query("""
                SELECT * FROM workflow_review_tasks
                WHERE subject_type = ? AND owner_actor_id = ?
                ORDER BY updated_at DESC, id DESC LIMIT 100
                """, mapper(), subjectType.name(), actor.actorId())
                .stream().map(this::withSubject).toList();
    }

    @Override
    @Transactional
    public ReviewTask decide(long reviewTaskId, Decision decision, String note) {
        CurrentActor actor = requireReviewer();
        ReviewTask task = jdbcTemplate.query("SELECT * FROM workflow_review_tasks WHERE id = ?",
                mapper(), reviewTaskId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (task.status() != Status.PENDING) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        requireReviewableSubject(task);
        if (!actor.hasRole(BusinessRole.ADMIN) && actor.actorId().equals(task.ownerActorId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "创建者不能复核自己的业务对象，请由独立复核员处理。");
        }
        Status target = decision == Decision.APPROVE ? Status.APPROVED : Status.REJECTED;
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE workflow_review_tasks
                SET status = ?, reviewer_actor_id = ?, review_note = ?, reviewed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING' AND content_version = ?
                """, target.name(), actor.actorId(), normalizeNote(note), Timestamp.from(now),
                Timestamp.from(now), reviewTaskId, task.contentVersion());
        if (updated != 1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        return withSubject(jdbcTemplate.query("SELECT * FROM workflow_review_tasks WHERE id = ?",
                mapper(), reviewTaskId).getFirst());
    }

    private void requireReviewableSubject(ReviewTask task) {
        if (task.subjectType() != SubjectType.REPORT_DRAFT) {
            return;
        }
        boolean reviewable;
        try {
            long draftId = Long.parseLong(task.subjectId());
            reviewable = Boolean.TRUE.equals(jdbcTemplate.query("""
                    SELECT status IN ('DRAFTED', 'NEEDS_REVIEW')
                    FROM report_drafts WHERE id = ?
                    """, (rs, rowNum) -> rs.getBoolean(1), draftId)
                    .stream().findFirst().orElse(false));
        } catch (NumberFormatException ex) {
            reviewable = false;
        }
        if (!reviewable) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该业务对象已结束，不能继续复核。");
        }
    }

    private ReviewTask requireTask(SubjectType subjectType, String subjectId) {
        return jdbcTemplate.query("""
                SELECT * FROM workflow_review_tasks
                WHERE subject_type = ? AND subject_id = ?
                """, mapper(), subjectType.name(), subjectId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ReviewTask withSubject(ReviewTask task) {
        Map<String, Object> subject = switch (task.subjectType()) {
            case DATA_SQL_CANDIDATE -> dataSubject(task.subjectId());
            case REPORT_DRAFT -> reportSubject(task.subjectId());
        };
        return new ReviewTask(task.id(), task.subjectType(), task.subjectId(), task.ownerActorId(),
                task.status(), task.reviewerActorId(), task.reviewNote(), task.contentVersion(),
                task.submittedAt(), task.reviewedAt(), task.updatedAt(), subject);
    }

    private Map<String, Object> dataSubject(String subjectId) {
        return jdbcTemplate.query("""
                SELECT candidate_id, sql_text, request_id, model_name, prompt_version,
                       policy_version, expires_at, created_at
                FROM data_sql_candidates WHERE candidate_id = ?
                """, (rs, rowNum) -> mapOfNullable(
                        "candidateId", rs.getString("candidate_id"),
                        "sql", rs.getString("sql_text"),
                        "requestId", rs.getString("request_id"),
                        "modelName", rs.getString("model_name"),
                        "promptVersion", rs.getString("prompt_version"),
                        "policyVersion", rs.getString("policy_version"),
                        "expiresAt", instant(rs.getTimestamp("expires_at")),
                        "createdAt", instant(rs.getTimestamp("created_at"))), subjectId)
                .stream().findFirst().orElse(Map.of());
    }

    private Map<String, Object> reportSubject(String subjectId) {
        long id;
        try {
            id = Long.parseLong(subjectId);
        } catch (NumberFormatException ex) {
            return Map.of();
        }
        return jdbcTemplate.query("""
                SELECT d.id, d.structured_content, d.cited_source_ids, d.review_reasons,
                       d.expires_at, d.created_at, r.title, r.report_type,
                       r.period_start, r.period_end
                FROM report_drafts d
                JOIN report_requests r ON r.id = d.request_id
                WHERE d.id = ?
                """, (rs, rowNum) -> mapOfNullable(
                        "draftId", rs.getLong("id"),
                        "title", rs.getString("title"),
                        "reportType", rs.getString("report_type"),
                        "periodStart", rs.getObject("period_start"),
                        "periodEnd", rs.getObject("period_end"),
                        "content", rs.getString("structured_content"),
                        "citedSourceIds", rs.getString("cited_source_ids"),
                        "reviewReasons", rs.getString("review_reasons"),
                        "expiresAt", instant(rs.getTimestamp("expires_at")),
                        "createdAt", instant(rs.getTimestamp("created_at"))), id)
                .stream().findFirst().orElse(Map.of());
    }

    private RowMapper<ReviewTask> mapper() {
        return (rs, rowNum) -> new ReviewTask(
                rs.getLong("id"), SubjectType.valueOf(rs.getString("subject_type")),
                rs.getString("subject_id"), rs.getString("owner_actor_id"),
                Status.valueOf(rs.getString("status")), rs.getString("reviewer_actor_id"),
                rs.getString("review_note"), rs.getLong("content_version"),
                instant(rs.getTimestamp("submitted_at")), instant(rs.getTimestamp("reviewed_at")),
                instant(rs.getTimestamp("updated_at")), Map.of());
    }

    private CurrentActor requireActor() {
        CurrentActor actor = actorProvider.currentActor();
        if (actor == null || !actor.authenticated()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return actor;
    }

    private CurrentActor requireReviewer() {
        CurrentActor actor = requireActor();
        if (!actor.hasRole(BusinessRole.ADMIN) && !actor.hasRole(BusinessRole.REVIEWER)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return actor;
    }

    private static void requireOwnerOrAdmin(CurrentActor actor, String ownerActorId) {
        if (!actor.hasRole(BusinessRole.ADMIN) && !actor.actorId().equals(ownerActorId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "复核意见不能为空。");
        }
        String normalized = note.trim();
        if (normalized.length() > 1000) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return normalized;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Map<String, Object> mapOfNullable(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }
}
