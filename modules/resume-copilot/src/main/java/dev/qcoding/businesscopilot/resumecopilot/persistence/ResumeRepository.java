package dev.qcoding.businesscopilot.resumecopilot.persistence;

import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.PromptTemplateMetadata;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 简历模块聚合对象、证据批次和审计事件的 JDBC 持久化。 */
public class ResumeRepository {
    private static final Logger log = LoggerFactory.getLogger(ResumeRepository.class);
    private final JdbcTemplate jdbcTemplate;

    public ResumeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ResumeJobEntity insertJob(ResumeJobEntity job) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO resume_jobs (title, sanitized_jd, criteria_json, status, criteria_token_digest, "
                            + "owner_actor_id, expires_at, logical_job_id, criteria_version, current_version, "
                            + "effective_from, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            statement.setString(1, job.getTitle());
            statement.setString(2, job.getSanitizedJd());
            statement.setString(3, job.getCriteriaJson());
            statement.setString(4, job.getStatus());
            statement.setString(5, job.getCriteriaTokenDigest());
            statement.setString(6, job.getOwnerActorId());
            statement.setTimestamp(7, timestamp(job.getExpiresAt()));
            statement.setObject(8, job.getLogicalJobId());
            statement.setInt(9, job.getCriteriaVersion());
            statement.setBoolean(10, job.isCurrentVersion());
            statement.setTimestamp(11, timestamp(job.getEffectiveFrom()));
            statement.setTimestamp(12, timestamp(job.getCreatedAt()));
            statement.setTimestamp(13, timestamp(job.getUpdatedAt()));
            return statement;
        }, keys);
        job.setId(keys.getKey().longValue());
        return job;
    }

    @Transactional
    public ResumeJobEntity insertJobVersion(ResumeJobEntity job) {
        jdbcTemplate.update(
                "UPDATE resume_jobs SET current_version = FALSE, updated_at = ? "
                        + "WHERE logical_job_id = ? AND current_version = TRUE",
                timestamp(job.getCreatedAt()), job.getLogicalJobId());
        return insertJob(job);
    }

    public int nextCriteriaVersion(UUID logicalJobId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(criteria_version), 0) + 1 FROM resume_jobs WHERE logical_job_id = ?",
                Integer.class, logicalJobId);
        return version == null ? 1 : version;
    }

    public ResumeJobEntity findCurrentJob(UUID logicalJobId) {
        List<ResumeJobEntity> jobs = jdbcTemplate.query(
                selectJobSql() + " WHERE logical_job_id = ? AND current_version = TRUE",
                this::mapJob, logicalJobId);
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    public ResumeJobEntity findJob(long id) {
        List<ResumeJobEntity> jobs = jdbcTemplate.query(
                selectJobSql() + " WHERE id = ?", this::mapJob, id);
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    /** Current confirmed standards visible to the owner, the administrator, or the system demo. */
    public List<ResumeJobEntity> findCurrentConfirmedJobs(String actorId, boolean admin) {
        return jdbcTemplate.query(selectJobSql() + " WHERE current_version = TRUE "
                        + "AND status = 'CRITERIA_CONFIRMED' "
                        + "AND (system_managed = TRUE OR owner_actor_id = ? OR ?) "
                        + "ORDER BY updated_at DESC",
                this::mapJob, actorId, admin);
    }

    private String selectJobSql() {
        return "SELECT id, title, sanitized_jd, criteria_json, status, criteria_token_digest, owner_actor_id, "
                + "action_actor_id, expires_at, logical_job_id, criteria_version, current_version, effective_from, "
                + "created_at, updated_at FROM resume_jobs";
    }

    private ResumeJobEntity mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        ResumeJobEntity job = new ResumeJobEntity();
        job.setId(rs.getLong("id"));
        job.setTitle(rs.getString("title"));
        job.setSanitizedJd(rs.getString("sanitized_jd"));
        job.setCriteriaJson(rs.getString("criteria_json"));
        job.setStatus(rs.getString("status"));
        job.setCriteriaTokenDigest(rs.getString("criteria_token_digest"));
        job.setOwnerActorId(rs.getString("owner_actor_id"));
        job.setActionActorId(rs.getString("action_actor_id"));
        job.setExpiresAt(instant(rs.getTimestamp("expires_at")));
        job.setLogicalJobId(rs.getObject("logical_job_id", UUID.class));
        job.setCriteriaVersion(rs.getInt("criteria_version"));
        job.setCurrentVersion(rs.getBoolean("current_version"));
        job.setEffectiveFrom(instant(rs.getTimestamp("effective_from")));
        job.setCreatedAt(instant(rs.getTimestamp("created_at")));
        job.setUpdatedAt(instant(rs.getTimestamp("updated_at")));
        return job;
    }

    public boolean confirmCriteria(long jobId, ResumeModels.Status expected,
                                   String actionActorId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE resume_jobs SET status = ?, criteria_token_digest = NULL, action_actor_id = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND expires_at > ?",
                ResumeModels.Status.CRITERIA_CONFIRMED.name(), actionActorId, timestamp(now),
                jobId, expected.name(), timestamp(now)) == 1;
    }

    public boolean updateDraftCriteria(long jobId, String criteriaJson, String tokenDigest,
                                       Instant expiresAt, Instant now) {
        return jdbcTemplate.update("""
                UPDATE resume_jobs
                SET criteria_json = ?, criteria_token_digest = ?, expires_at = ?, updated_at = ?
                WHERE id = ? AND status = 'CRITERIA_DRAFTED' AND current_version = TRUE
                """, criteriaJson, tokenDigest, timestamp(expiresAt), timestamp(now), jobId) == 1;
    }

    public long insertSubmission(long jobId, String sanitizedResume, String fileName,
                                 String contentType, Instant expiresAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO resume_submissions (job_id, anonymous_candidate_id, sanitized_resume, content_hash, "
                            + "source_file_name, source_content_type, expires_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            statement.setLong(1, jobId);
            statement.setString(2, "candidate-" + UUID.randomUUID().toString().substring(0, 8));
            statement.setString(3, sanitizedResume);
            statement.setString(4, sha256(sanitizedResume));
            statement.setString(5, fileName == null || fileName.isBlank() ? "text-input.txt" : fileName);
            statement.setString(6, contentType == null || contentType.isBlank() ? "text/plain" : contentType);
            statement.setTimestamp(7, timestamp(expiresAt));
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    @Transactional
    public long persistSubmission(long jobId, String sanitizedResume, List<ResumeModels.ResumeEvidence> evidence,
                                  String fileName, String contentType, Instant expiresAt) {
        long submissionId = insertSubmission(jobId, sanitizedResume, fileName, contentType, expiresAt);
        insertEvidence(submissionId, evidence);
        return submissionId;
    }

    public void insertEvidence(long submissionId, List<ResumeModels.ResumeEvidence> evidence) {
        for (ResumeModels.ResumeEvidence item : evidence) {
            jdbcTemplate.update(
                    "INSERT INTO resume_evidence (submission_id, evidence_ref, section_name, sanitized_text, position_index, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    submissionId, item.evidenceId(), item.section(), item.sanitizedText(),
                    item.positionIndex(), Timestamp.from(Instant.now()));
        }
    }

    public List<ResumeModels.ResumeEvidence> findEvidence(long submissionId) {
        return jdbcTemplate.query(
                "SELECT evidence_ref, section_name, sanitized_text, position_index "
                        + "FROM resume_evidence WHERE submission_id = ? ORDER BY position_index",
                (rs, rowNum) -> new ResumeModels.ResumeEvidence(
                        rs.getString("evidence_ref"), rs.getString("section_name"),
                        rs.getString("sanitized_text"), rs.getInt("position_index")),
                submissionId);
    }

    public ResumeAssessmentEntity insertAssessment(ResumeAssessmentEntity assessment) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO resume_assessments (job_id, submission_id, content_json, status, review_reasons, "
                            + "review_token_digest, owner_actor_id, review_queue, reviewer_actor_id, expires_at, "
                            + "criteria_version, original_content_json, corrected_content_json, reviewer_feedback, "
                            + "decision_outcome, reviewed_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            statement.setLong(1, assessment.getJobId());
            statement.setLong(2, assessment.getSubmissionId());
            statement.setString(3, assessment.getContentJson());
            statement.setString(4, assessment.getStatus());
            statement.setString(5, assessment.getReviewReasons());
            statement.setString(6, assessment.getReviewTokenDigest());
            statement.setString(7, assessment.getOwnerActorId());
            statement.setBoolean(8, assessment.isReviewQueue());
            statement.setString(9, assessment.getReviewerActorId());
            statement.setTimestamp(10, timestamp(assessment.getExpiresAt()));
            statement.setInt(11, assessment.getCriteriaVersion());
            statement.setString(12, assessment.getOriginalContentJson());
            statement.setString(13, assessment.getCorrectedContentJson());
            statement.setString(14, assessment.getReviewerFeedback());
            statement.setString(15, assessment.getDecisionOutcome());
            statement.setTimestamp(16, timestamp(assessment.getReviewedAt()));
            statement.setTimestamp(17, timestamp(assessment.getCreatedAt()));
            statement.setTimestamp(18, timestamp(assessment.getUpdatedAt()));
            return statement;
        }, keys);
        assessment.setId(keys.getKey().longValue());
        return assessment;
    }

    public ResumeAssessmentEntity findAssessment(long id) {
        List<ResumeAssessmentEntity> assessments = jdbcTemplate.query(
                "SELECT id, job_id, submission_id, content_json, status, review_reasons, review_token_digest, "
                        + "owner_actor_id, review_queue, reviewer_actor_id, action_actor_id, expires_at, "
                        + "criteria_version, original_content_json, corrected_content_json, reviewer_feedback, "
                        + "decision_outcome, reviewed_at, created_at, updated_at "
                        + "FROM resume_assessments WHERE id = ?",
                (rs, rowNum) -> {
                    ResumeAssessmentEntity assessment = new ResumeAssessmentEntity();
                    assessment.setId(rs.getLong("id"));
                    assessment.setJobId(rs.getLong("job_id"));
                    assessment.setSubmissionId(rs.getLong("submission_id"));
                    assessment.setContentJson(rs.getString("content_json"));
                    assessment.setStatus(rs.getString("status"));
                    assessment.setReviewReasons(rs.getString("review_reasons"));
                    assessment.setReviewTokenDigest(rs.getString("review_token_digest"));
                    assessment.setOwnerActorId(rs.getString("owner_actor_id"));
                    assessment.setReviewQueue(rs.getBoolean("review_queue"));
                    assessment.setReviewerActorId(rs.getString("reviewer_actor_id"));
                    assessment.setActionActorId(rs.getString("action_actor_id"));
                    assessment.setExpiresAt(instant(rs.getTimestamp("expires_at")));
                    assessment.setCriteriaVersion(rs.getInt("criteria_version"));
                    assessment.setOriginalContentJson(rs.getString("original_content_json"));
                    assessment.setCorrectedContentJson(rs.getString("corrected_content_json"));
                    assessment.setReviewerFeedback(rs.getString("reviewer_feedback"));
                    assessment.setDecisionOutcome(rs.getString("decision_outcome"));
                    assessment.setReviewedAt(instant(rs.getTimestamp("reviewed_at")));
                    assessment.setCreatedAt(instant(rs.getTimestamp("created_at")));
                    assessment.setUpdatedAt(instant(rs.getTimestamp("updated_at")));
                    return assessment;
                }, id);
        return assessments.isEmpty() ? null : assessments.getFirst();
    }

    public boolean transitionAssessment(long id, ResumeModels.Status expected, ResumeModels.Status target,
                                        String actionActorId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE resume_assessments SET status = ?, review_token_digest = NULL, action_actor_id = ?, "
                        + "decision_outcome = CASE WHEN ? = 'CANCELED' THEN 'REJECTED' ELSE decision_outcome END, "
                        + "reviewed_at = CASE WHEN ? = 'CANCELED' THEN ? ELSE reviewed_at END, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND expires_at > ?",
                target.name(), actionActorId, target.name(), target.name(), timestamp(now),
                timestamp(now), id, expected.name(), timestamp(now)) == 1;
    }

    public boolean reviewAssessment(long id, ResumeModels.Status expected,
                                    String correctedContentJson, String reviewerFeedback,
                                    String outcome, String actionActorId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE resume_assessments SET content_json = ?, corrected_content_json = ?, reviewer_feedback = ?, "
                        + "decision_outcome = ?, status = ?, review_token_digest = NULL, action_actor_id = ?, "
                        + "reviewer_actor_id = ?, reviewed_at = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND expires_at > ?",
                correctedContentJson, correctedContentJson, reviewerFeedback, outcome,
                ResumeModels.Status.REVIEWED.name(), actionActorId, actionActorId,
                timestamp(now), timestamp(now), id, expected.name(), timestamp(now)) == 1;
    }

    public int deleteExpiredSubmissions(Instant now) {
        return jdbcTemplate.update(
                "DELETE FROM resume_submissions WHERE expires_at <= ? OR deleted_at IS NOT NULL",
                timestamp(now));
    }

    public boolean deleteSubmission(long submissionId, String ownerActorId, boolean admin) {
        if (admin) {
            return jdbcTemplate.update("DELETE FROM resume_submissions WHERE id = ?", submissionId) == 1;
        }
        return jdbcTemplate.update(
                "DELETE FROM resume_submissions submission USING resume_jobs job "
                        + "WHERE submission.id = ? AND submission.job_id = job.id AND job.owner_actor_id = ?",
                submissionId, ownerActorId) == 1;
    }

    public void audit(String eventType, Long jobId, Long submissionId, Long assessmentId, int criteriaCount,
                      int evidenceCount, String modelName, String status, String errorMessage) {
        audit(eventType, jobId, submissionId, assessmentId, criteriaCount, evidenceCount,
                modelName, status, errorMessage, null, null, null,
                null, null, BusinessRequestContextHolder.currentActorId(), null);
    }

    public void audit(String eventType, Long jobId, Long submissionId, Long assessmentId,
                      int criteriaCount, int evidenceCount, String modelName, String status,
                      String errorMessage, PromptTemplateMetadata promptMetadata,
                      AiInvocationMetadata aiMetadata, String policyVersion,
                      String violationCodes, Long latencyMs,
                      String creatorActorId, String actionActorId) {
        try {
            persistAudit(eventType, jobId, submissionId, assessmentId, criteriaCount,
                    evidenceCount, modelName, status, errorMessage, promptMetadata,
                    aiMetadata, policyVersion, violationCodes, latencyMs,
                    creatorActorId, actionActorId);
        } catch (RuntimeException ex) {
            log.warn("简历模块审计事件写入失败：eventType={}，status={}", eventType, status, ex);
        }
    }

    public void auditRequired(String eventType, Long jobId, Long submissionId, Long assessmentId,
                              int criteriaCount, int evidenceCount, String modelName, String status,
                              String errorMessage, String creatorActorId, String actionActorId) {
        persistAudit(eventType, jobId, submissionId, assessmentId, criteriaCount,
                evidenceCount, modelName, status, errorMessage,
                null, null, null, null, null, creatorActorId, actionActorId);
    }

    private void persistAudit(String eventType, Long jobId, Long submissionId, Long assessmentId,
                              int criteriaCount, int evidenceCount, String modelName, String status,
                              String errorMessage, PromptTemplateMetadata promptMetadata,
                              AiInvocationMetadata aiMetadata, String policyVersion,
                              String violationCodes, Long latencyMs,
                              String creatorActorId, String actionActorId) {
        jdbcTemplate.update(
                "INSERT INTO resume_audit_logs (request_id, http_request_id, actor_id, job_id, submission_id, "
                        + "assessment_id, event_type, criteria_count, evidence_count, model_name, status, "
                        + "error_message, creator_actor_id, action_actor_id, provider_name, provider_request_id, "
                        + "prompt_name, prompt_version, prompt_hash, policy_version, violation_codes, input_tokens, "
                        + "output_tokens, finish_reason, latency_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), BusinessRequestContextHolder.currentRequestId(),
                BusinessRequestContextHolder.currentActorId(), jobId, submissionId, assessmentId,
                eventType, criteriaCount, evidenceCount, modelName, status, errorMessage,
                creatorActorId, actionActorId,
                aiMetadata != null ? aiMetadata.providerName() : null,
                aiMetadata != null ? aiMetadata.providerRequestId() : null,
                promptMetadata != null ? promptMetadata.name() : null,
                promptMetadata != null ? promptMetadata.version() : null,
                promptMetadata != null ? promptMetadata.contentHash() : null,
                policyVersion, violationCodes,
                aiMetadata != null ? aiMetadata.inputTokens() : null,
                aiMetadata != null ? aiMetadata.outputTokens() : null,
                aiMetadata != null ? aiMetadata.finishReason() : null,
                latencyMs);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
