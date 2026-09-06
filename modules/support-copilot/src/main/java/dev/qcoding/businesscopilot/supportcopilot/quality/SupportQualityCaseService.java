package dev.qcoding.businesscopilot.supportcopilot.quality;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * SUP-05：把复核反馈转成可管理的质量案例。
 *
 * <p>案例内容必须使用虚构或经授权脱敏的内容：摘要与修订原因在入库前统一脱敏，
 * 不直接复制客户原文。案例关联问题类型、修订原因和草稿版本，
 * 用于固定失败案例库与后续评测集建设。</p>
 */
public class SupportQualityCaseService {

    private static final Set<String> CASE_TYPES = Set.of(
            "NO_EVIDENCE", "EVIDENCE_EXPIRED", "RISKY_PROMISE",
            "PERMISSION_LIMIT", "TOOL_FAILURE", "REVIEW_EDIT");

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider actorProvider;
    private final SensitiveTextMasker sensitiveTextMasker;

    public SupportQualityCaseService(JdbcTemplate jdbcTemplate,
                                     CurrentActorProvider actorProvider,
                                     SensitiveTextMasker sensitiveTextMasker) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorProvider = actorProvider;
        this.sensitiveTextMasker = sensitiveTextMasker;
    }

    public QualityCase record(QualityCaseCommand command) {
        CurrentActor actor = requireReviewer();
        if (command == null || command.draftId() == null || command.failureSummary() == null
                || command.failureSummary().isBlank() || command.revisionReason() == null
                || command.revisionReason().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "质量案例必须关联真实草稿并填写修订原因");
        }
        validateCaseType(command.caseType());
        DraftContext context = requireDraftContext(command.draftId());
        if (command.ticketRef() != null && !command.ticketRef().isBlank()
                && !command.ticketRef().equals(context.ticketRef())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "工单引用与草稿不匹配");
        }
        String summary = sensitiveTextMasker.mask(command.failureSummary().trim());
        String revisionReason = sensitiveTextMasker.mask(command.revisionReason().trim());
        return insert(context, command.caseType(), summary, revisionReason, actor.actorId());
    }

    /** 人工修改待复核草稿后自动形成质量案例，避免依赖可伪造的手工引用。 */
    public QualityCase recordReviewEdit(long draftId, String revisionReason) {
        CurrentActor actor = requireReviewer();
        if (revisionReason == null || revisionReason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "人工修改原因不能为空");
        }
        DraftContext context = requireDraftContext(draftId);
        return insert(context, "REVIEW_EDIT", "人工复核修改了回复草稿",
                sensitiveTextMasker.mask(revisionReason.trim()), actor.actorId());
    }

    private QualityCase insert(DraftContext context, String caseType, String summary,
                               String revisionReason, String actorId) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO support_quality_cases (
                    ticket_ref, case_type, failure_summary, revision_reason,
                    draft_id, draft_version, created_by, ticket_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                context.ticketRef(), caseType, summary, revisionReason,
                context.draftId(), context.draftVersion(), actorId, context.ticketId());
        return new QualityCase(id, context.ticketRef(), caseType, summary,
                revisionReason, context.draftId(), context.draftVersion(), actorId, Instant.now());
    }

    public List<QualityCase> list(String caseType) {
        requireReviewer();
        if (caseType != null && !caseType.isBlank()) validateCaseType(caseType);
        if (caseType == null || caseType.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT id, ticket_ref, case_type, failure_summary, revision_reason,
                           draft_id, draft_version, created_by, created_at
                    FROM support_quality_cases ORDER BY created_at DESC, id DESC LIMIT 100
                    """, ROW_MAPPER);
        }
        return jdbcTemplate.query("""
                SELECT id, ticket_ref, case_type, failure_summary, revision_reason,
                       draft_id, draft_version, created_by, created_at
                FROM support_quality_cases WHERE case_type = ?
                ORDER BY created_at DESC, id DESC LIMIT 100
                """, ROW_MAPPER, caseType);
    }

    private CurrentActor requireReviewer() {
        CurrentActor actor = actorProvider.currentActor();
        if (!actor.hasRole(BusinessRole.REVIEWER) && !actor.hasRole(BusinessRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return actor;
    }

    private void validateCaseType(String caseType) {
        if (caseType == null || !CASE_TYPES.contains(caseType)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不支持的质量案例类型");
        }
    }

    private DraftContext requireDraftContext(long draftId) {
        List<DraftContext> rows = jdbcTemplate.query("""
                SELECT d.id AS draft_id, d.ticket_id,
                       COALESCE(t.external_id, t.id::text) AS ticket_ref,
                       concat_ws(':', COALESCE(d.knowledge_version_ids, 'none'),
                           extract(epoch from d.updated_at)::bigint::text) AS draft_version
                FROM support_reply_drafts d
                JOIN support_tickets t ON t.id = d.ticket_id
                WHERE d.id = ? AND d.edited_at IS NOT NULL
                """, (rs, rowNum) -> new DraftContext(
                rs.getLong("draft_id"), rs.getLong("ticket_id"),
                rs.getString("ticket_ref"), rs.getString("draft_version")), draftId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private static final org.springframework.jdbc.core.RowMapper<QualityCase> ROW_MAPPER =
            (rs, rowNum) -> new QualityCase(
                    rs.getLong("id"),
                    rs.getString("ticket_ref"),
                    rs.getString("case_type"),
                    rs.getString("failure_summary"),
                    rs.getString("revision_reason"),
                    rs.getObject("draft_id", Long.class),
                    rs.getString("draft_version"),
                    rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant());

    public record QualityCaseCommand(String ticketRef, String caseType, String failureSummary,
                                     String revisionReason, Long draftId, String draftVersion) {
    }

    public record QualityCase(Long id, String ticketRef, String caseType, String failureSummary,
                              String revisionReason, Long draftId, String draftVersion,
                              String createdBy, Instant createdAt) {
    }

    private record DraftContext(long draftId, long ticketId, String ticketRef, String draftVersion) {
    }
}
