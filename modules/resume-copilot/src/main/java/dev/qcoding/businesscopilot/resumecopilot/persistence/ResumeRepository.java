package dev.qcoding.businesscopilot.resumecopilot.persistence;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Hybrid persistence: MyBatis-Plus for stable aggregates, JDBC for evidence batches and audit events. */
public class ResumeRepository {
    private static final Logger log = LoggerFactory.getLogger(ResumeRepository.class);
    private final ResumeJobMapper jobMapper;
    private final ResumeAssessmentMapper assessmentMapper;
    private final JdbcTemplate jdbcTemplate;

    public ResumeRepository(ResumeJobMapper jobMapper, ResumeAssessmentMapper assessmentMapper, JdbcTemplate jdbcTemplate) {
        this.jobMapper = jobMapper;
        this.assessmentMapper = assessmentMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ResumeJobEntity insertJob(ResumeJobEntity job) {
        jobMapper.insert(job);
        return job;
    }

    public ResumeJobEntity findJob(long id) {
        return jobMapper.selectById(id);
    }

    public boolean confirmCriteria(long jobId, String token, Instant now) {
        return jobMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ResumeJobEntity>()
                .eq(ResumeJobEntity::getId, jobId)
                .eq(ResumeJobEntity::getStatus, ResumeModels.Status.CRITERIA_DRAFTED.name())
                .eq(ResumeJobEntity::getCriteriaToken, token)
                .gt(ResumeJobEntity::getExpiresAt, now)
                .set(ResumeJobEntity::getStatus, ResumeModels.Status.CRITERIA_CONFIRMED.name())
                .set(ResumeJobEntity::getCriteriaToken, null)
                .set(ResumeJobEntity::getUpdatedAt, now)) == 1;
    }

    public long insertSubmission(long jobId, String sanitizedResume) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO resume_submissions (job_id, anonymous_candidate_id, sanitized_resume, content_hash, created_at) VALUES (?, ?, ?, ?, ?)",
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
            jdbcTemplate.update("INSERT INTO resume_evidence (submission_id, evidence_ref, section_name, sanitized_text, position_index, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    submissionId, item.evidenceId(), item.section(), item.sanitizedText(), item.positionIndex(),
                    Timestamp.from(Instant.now()));
        }
    }

    public ResumeAssessmentEntity insertAssessment(ResumeAssessmentEntity assessment) {
        assessmentMapper.insert(assessment);
        return assessment;
    }

    public ResumeAssessmentEntity findAssessment(long id) {
        return assessmentMapper.selectById(id);
    }

    public boolean transitionAssessment(long id, String token, ResumeModels.Status expected, ResumeModels.Status target) {
        return assessmentMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ResumeAssessmentEntity>()
                        .eq(ResumeAssessmentEntity::getId, id)
                        .eq(ResumeAssessmentEntity::getStatus, expected.name())
                        .eq(ResumeAssessmentEntity::getReviewToken, token)
                        .gt(ResumeAssessmentEntity::getExpiresAt, Instant.now())
                        .set(ResumeAssessmentEntity::getStatus, target.name())
                        .set(ResumeAssessmentEntity::getReviewToken, null)
                        .set(ResumeAssessmentEntity::getUpdatedAt, Instant.now())) == 1;
    }

    public void audit(String eventType, Long jobId, Long submissionId, Long assessmentId, int criteriaCount,
                      int evidenceCount, String modelName, String status, String errorMessage) {
        try {
            jdbcTemplate.update("INSERT INTO resume_audit_logs (request_id, http_request_id, actor_id, job_id, submission_id, assessment_id, event_type, criteria_count, evidence_count, model_name, status, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), BusinessRequestContextHolder.currentRequestId(),
                    BusinessRequestContextHolder.currentActorId(),
                    jobId, submissionId, assessmentId, eventType, criteriaCount,
                    evidenceCount, modelName, status, errorMessage);
        } catch (RuntimeException ex) {
            log.warn("Unable to persist Resume Copilot audit event type={} status={}", eventType, status, ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
