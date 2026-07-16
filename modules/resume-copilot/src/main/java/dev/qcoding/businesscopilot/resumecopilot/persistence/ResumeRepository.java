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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** JDBC persistence for Resume Copilot aggregates, evidence batches, and audit events. */
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
                            + "owner_actor_id, expires_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, job.getTitle());
            statement.setString(2, job.getSanitizedJd());
            statement.setString(3, job.getCriteriaJson());
            statement.setString(4, job.getStatus());
            statement.setString(5, job.getCriteriaTokenDigest());
            statement.setString(6, job.getOwnerActorId());
            statement.setTimestamp(7, timestamp(job.getExpiresAt()));
            statement.setTimestamp(8, timestamp(job.getCreatedAt()));
            statement.setTimestamp(9, timestamp(job.getUpdatedAt()));
            return statement;
        }, keys);
        job.setId(keys.getKey().longValue());
        return job;
    }

    public ResumeJobEntity findJob(long id) {
        List<ResumeJobEntity> jobs = jdbcTemplate.query(
                "SELECT id, title, sanitized_jd, criteria_json, status, criteria_token_digest, owner_actor_id, "
                        + "action_actor_id, expires_at, created_at, updated_at FROM resume_jobs WHERE id = ?",
                (rs, rowNum) -> {
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
                    job.setCreatedAt(instant(rs.getTimestamp("created_at")));
                    job.setUpdatedAt(instant(rs.getTimestamp("updated_at")));
                    return job;
                }, id);
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    public boolean confirmCriteria(long jobId, ResumeModels.Status expected,
                                   String actionActorId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE resume_jobs SET status = ?, criteria_token_digest = NULL, action_actor_id = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND expires_at > ?",
                ResumeModels.Status.CRITERIA_CONFIRMED.name(), actionActorId, timestamp(now),
                jobId, expected.name(), timestamp(now)) == 1;
    }

    public long insertSubmission(long jobId, String sanitizedResume) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO resume_submissions (job_id, anonymous_candidate_id, sanitized_resume, content_hash, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, jobId);
            statement.setString(2, "candidate-" + UUID.randomUUID().toString().substring(0, 8));
            statement.setString(3, sanitizedResume);
            statement.setString(4, sha256(sanitizedResume));
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    @Transactional
    public long persistSubmission(long jobId, String sanitizedResume, List<ResumeModels.ResumeEvidence> evidence) {
        long submissionId = insertSubmission(jobId, sanitizedResume);
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

    public ResumeAssessmentEntity insertAssessment(ResumeAssessmentEntity assessment) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO resume_assessments (job_id, submission_id, content_json, status, review_reasons, "
                            + "review_token_digest, owner_actor_id, review_queue, reviewer_actor_id, expires_at, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
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
            statement.setTimestamp(11, timestamp(assessment.getCreatedAt()));
            statement.setTimestamp(12, timestamp(assessment.getUpdatedAt()));
            return statement;
        }, keys);
        assessment.setId(keys.getKey().longValue());
        return assessment;
    }

    public ResumeAssessmentEntity findAssessment(long id) {
        List<ResumeAssessmentEntity> assessments = jdbcTemplate.query(
                "SELECT id, job_id, submission_id, content_json, status, review_reasons, review_token_digest, "
                        + "owner_actor_id, review_queue, reviewer_actor_id, action_actor_id, expires_at, created_at, updated_at "
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
                    assessment.setCreatedAt(instant(rs.getTimestamp("created_at")));
                    assessment.setUpdatedAt(instant(rs.getTimestamp("updated_at")));
                    return assessment;
                }, id);
        return assessments.isEmpty() ? null : assessments.getFirst();
    }

    public boolean transitionAssessment(long id, ResumeModels.Status expected, ResumeModels.Status target,
                                        String actionActorId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE resume_assessments SET status = ?, review_token_digest = NULL, action_actor_id = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND expires_at > ?",
                target.name(), actionActorId, timestamp(now), id, expected.name(), timestamp(now)) == 1;
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
            log.warn("Unable to persist Resume Copilot audit event type={} status={}", eventType, status, ex);
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
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
