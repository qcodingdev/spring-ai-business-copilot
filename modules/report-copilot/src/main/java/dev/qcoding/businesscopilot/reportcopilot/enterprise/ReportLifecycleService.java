package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraft;
import dev.qcoding.businesscopilot.reportcopilot.draft.ReportDraftStatus;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/** Short database transactions for report leases and publication; no model or HTTP calls. */
public class ReportLifecycleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReportLifecycleService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ReportEnterpriseService.DueSchedule claimSchedule() {
        UUID token = UUID.randomUUID();
        var rows = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM report_schedules
                    WHERE enabled = TRUE AND next_run_at <= now() AND claimed_at IS NULL
                    ORDER BY next_run_at FOR UPDATE SKIP LOCKED LIMIT 1
                ), claimed AS (
                    UPDATE report_schedules schedule
                    SET claim_token = ?, claimed_at = now(), updated_at = now()
                    FROM candidate WHERE schedule.id = candidate.id RETURNING schedule.*
                ), started AS (
                    INSERT INTO report_schedule_runs(schedule_id, status)
                    SELECT id, 'RUNNING' FROM claimed RETURNING id, schedule_id
                )
                SELECT claimed.*, started.id AS run_id FROM claimed
                JOIN started ON started.schedule_id = claimed.id
                """, (rs, row) -> new ReportEnterpriseService.DueSchedule(
                rs.getLong("id"), rs.getString("schedule_key"), ReportType.valueOf(rs.getString("report_type")),
                rs.getString("title_template"), rs.getString("cron_expression"), rs.getString("zone_id"),
                rs.getString("template_id"), rs.getString("template_version"),
                json.readValue(rs.getString("source_config"), ReportEnterpriseService.SourceSelection.class),
                rs.getString("locale"), rs.getString("owner_actor_id"), token, rs.getLong("run_id")), token);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Scheduled(fixedDelayString = "${business-copilot.report-copilot.lease-reconcile-delay:PT1M}")
    @Transactional
    public void expireScheduleLeases() {
        // Lock schedules first, just like publication, to keep a consistent lock order.
        List<Long> stale = jdbc.queryForList("""
                SELECT id FROM report_schedules
                WHERE claimed_at < now() - interval '15 minutes'
                ORDER BY claimed_at LIMIT 100 FOR UPDATE SKIP LOCKED
                """, Long.class);
        for (Long id : stale) {
            jdbc.update("""
                    UPDATE report_schedule_runs SET status='FAILED', reason='SCHEDULE_LEASE_EXPIRED', finished_at=now()
                    WHERE schedule_id=? AND status='RUNNING'
                    """, id);
            jdbc.update("UPDATE report_schedules SET claimed_at=NULL, claim_token=NULL WHERE id=?", id);
        }
    }

    /** Invoked in the same transaction as draft insertion, before any draft becomes visible. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockPublication(ReportPublicationClaim claim) {
        if (claim.scheduleId() != null && !lockSchedule(claim)) throw lostLease();
        for (String reference : claim.allReferences()) {
            boolean consumedByThisRun = claim.handoffReferences().contains(reference);
            var ids = jdbc.queryForList("""
                    SELECT handoff.id FROM data_report_handoffs handoff
                    JOIN data_query_results result ON result.id=handoff.query_result_id
                    WHERE handoff.source_reference=? AND handoff.owner_actor_id=?
                      AND result.expires_at > now()
                      AND ((? AND handoff.status='CLAIMED' AND handoff.claim_token=?
                            AND handoff.claimed_at > now() - interval '15 minutes')
                           OR (NOT ? AND handoff.status IN ('READY', 'CONSUMED')))
                    FOR UPDATE OF handoff, result
                    """, Long.class, reference, claim.ownerActorId(), consumedByThisRun, claim.handoffToken(), consumedByThisRun);
            if (ids.size() != 1) throw lostLease();
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void completePublication(ReportPublicationClaim claim, ReportDraft draft) {
        if (!draft.ownerActorId().equals(claim.ownerActorId())) throw lostLease();
        boolean accepted = draft.status() == ReportDraftStatus.DRAFTED;
        for (String reference : claim.handoffReferences()) {
            int updated = jdbc.update("""
                    UPDATE data_report_handoffs
                    SET status=?, consumed_at=CASE WHEN ? THEN now() ELSE consumed_at END,
                        claim_token=NULL, claimed_at=NULL
                    WHERE source_reference=? AND owner_actor_id=? AND claim_token=? AND status='CLAIMED'
                    """, accepted ? "CONSUMED" : "READY", accepted, reference, claim.ownerActorId(), claim.handoffToken());
            if (updated != 1) throw lostLease();
        }
        if (accepted) {
            for (String reference : claim.allReferences()) {
                int linked = jdbc.update("""
                        INSERT INTO report_draft_data_links(draft_id, source_reference, query_result_id, candidate_id, handoff_id)
                        SELECT ?, handoff.source_reference, result.id, result.candidate_id, handoff.id
                        FROM data_report_handoffs handoff JOIN data_query_results result ON result.id=handoff.query_result_id
                        WHERE handoff.source_reference=? AND handoff.owner_actor_id=?
                        """, draft.id(), reference, claim.ownerActorId());
                if (linked != 1) throw lostLease();
            }
        }
        if (claim.scheduleId() != null) {
            int updated = jdbc.update("""
                    UPDATE report_schedule_runs SET status=?, report_draft_id=?, finished_at=now()
                    WHERE id=? AND schedule_id=? AND status='RUNNING'
                    """, draft.status().name(), draft.id(), claim.scheduleRunId(), claim.scheduleId());
            if (updated != 1) throw lostLease();
            advanceSchedule(claim);
        }
    }

    @Transactional
    public void failSchedule(ReportPublicationClaim claim, String reason) {
        if (!lockSchedule(claim)) return; // A recovered run or later lease owns the outcome now.
        jdbc.update("""
                UPDATE report_schedule_runs SET status='FAILED', reason=?, finished_at=now()
                WHERE id=? AND schedule_id=? AND status='RUNNING'
                """, reason, claim.scheduleRunId(), claim.scheduleId());
        advanceSchedule(claim);
    }

    private boolean lockSchedule(ReportPublicationClaim claim) {
        return jdbc.queryForList("""
                SELECT schedule.id FROM report_schedules schedule
                JOIN report_schedule_runs run ON run.schedule_id=schedule.id
                WHERE schedule.id=? AND schedule.claim_token=? AND schedule.enabled=TRUE
                  AND schedule.owner_actor_id=? AND schedule.claimed_at > now() - interval '15 minutes'
                  AND run.id=? AND run.status='RUNNING'
                FOR UPDATE OF schedule, run
                """, Long.class, claim.scheduleId(), claim.scheduleToken(), claim.ownerActorId(), claim.scheduleRunId()).size() == 1;
    }

    private void advanceSchedule(ReportPublicationClaim claim) {
        var next = jdbc.queryForObject("""
                SELECT cron_expression, zone_id FROM report_schedules WHERE id=? AND claim_token=?
                """, (rs, row) -> CronExpression.parse(rs.getString("cron_expression"))
                .next(ZonedDateTime.now(ZoneId.of(rs.getString("zone_id")))), claim.scheduleId(), claim.scheduleToken());
        int updated = jdbc.update("""
                UPDATE report_schedules SET last_run_at=now(), next_run_at=?, enabled=?,
                    claim_token=NULL, claimed_at=NULL, updated_at=now()
                WHERE id=? AND claim_token=?
                """, next == null ? null : Timestamp.from(next.toInstant()), next != null, claim.scheduleId(), claim.scheduleToken());
        if (updated != 1) throw lostLease();
    }

    private BusinessException lostLease() {
        return new BusinessException(ErrorCode.STATE_CONFLICT, "报告来源或排期的处理权已失效，草稿未发布，请刷新后重试");
    }
}
