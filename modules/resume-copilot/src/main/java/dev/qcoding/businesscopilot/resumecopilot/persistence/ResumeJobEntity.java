package dev.qcoding.businesscopilot.resumecopilot.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("resume_jobs")
public class ResumeJobEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String sanitizedJd;
    private String criteriaJson;
    private String status;
    private String criteriaToken;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

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
    public String getCriteriaToken() { return criteriaToken; }
    public void setCriteriaToken(String criteriaToken) { this.criteriaToken = criteriaToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
