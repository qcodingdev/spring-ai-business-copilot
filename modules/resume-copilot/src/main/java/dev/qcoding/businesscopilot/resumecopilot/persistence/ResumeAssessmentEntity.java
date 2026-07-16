package dev.qcoding.businesscopilot.resumecopilot.persistence;

import java.time.Instant;

public class ResumeAssessmentEntity {
    private Long id;
    private Long jobId;
    private Long submissionId;
    private String contentJson;
    private String status;
    private String reviewReasons;
    private String reviewTokenDigest;
    private String ownerActorId;
    private boolean reviewQueue;
    private String reviewerActorId;
    private String actionActorId;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReviewReasons() { return reviewReasons; }
    public void setReviewReasons(String reviewReasons) { this.reviewReasons = reviewReasons; }
    public String getReviewTokenDigest() { return reviewTokenDigest; }
    public void setReviewTokenDigest(String reviewTokenDigest) { this.reviewTokenDigest = reviewTokenDigest; }
    public String getOwnerActorId() { return ownerActorId; }
    public void setOwnerActorId(String ownerActorId) { this.ownerActorId = ownerActorId; }
    public boolean isReviewQueue() { return reviewQueue; }
    public void setReviewQueue(boolean reviewQueue) { this.reviewQueue = reviewQueue; }
    public String getReviewerActorId() { return reviewerActorId; }
    public void setReviewerActorId(String reviewerActorId) { this.reviewerActorId = reviewerActorId; }
    public String getActionActorId() { return actionActorId; }
    public void setActionActorId(String actionActorId) { this.actionActorId = actionActorId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
