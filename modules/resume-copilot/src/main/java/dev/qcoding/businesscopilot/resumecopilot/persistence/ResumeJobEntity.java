package dev.qcoding.businesscopilot.resumecopilot.persistence;

import java.time.Instant;
import java.util.UUID;

public class ResumeJobEntity {
    private Long id;
    private String title;
    private String sanitizedJd;
    private String criteriaJson;
    private String status;
    private String criteriaTokenDigest;
    private String ownerActorId;
    private String actionActorId;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID logicalJobId;
    private int criteriaVersion;
    private boolean currentVersion;
    private Instant effectiveFrom;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSanitizedJd() { return sanitizedJd; }
    public void setSanitizedJd(String sanitizedJd) { this.sanitizedJd = sanitizedJd; }
    public String getCriteriaJson() { return criteriaJson; }
    public void setCriteriaJson(String criteriaJson) { this.criteriaJson = criteriaJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCriteriaTokenDigest() { return criteriaTokenDigest; }
    public void setCriteriaTokenDigest(String criteriaTokenDigest) { this.criteriaTokenDigest = criteriaTokenDigest; }
    public String getOwnerActorId() { return ownerActorId; }
    public void setOwnerActorId(String ownerActorId) { this.ownerActorId = ownerActorId; }
    public String getActionActorId() { return actionActorId; }
    public void setActionActorId(String actionActorId) { this.actionActorId = actionActorId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getLogicalJobId() { return logicalJobId; }
    public void setLogicalJobId(UUID logicalJobId) { this.logicalJobId = logicalJobId; }
    public int getCriteriaVersion() { return criteriaVersion; }
    public void setCriteriaVersion(int criteriaVersion) { this.criteriaVersion = criteriaVersion; }
    public boolean isCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(boolean currentVersion) { this.currentVersion = currentVersion; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
}
