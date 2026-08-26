package dev.qcoding.businesscopilot.resumecopilot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "business-copilot.resume-copilot")
public record ResumeCopilotProperties(boolean enabled, int maxJobDescriptionLength, int maxResumeLength,
                                      int maxCriteriaCount, int maxEvidenceCount, Duration reviewTokenTtl,
                                      boolean protectedAttributeGuardEnabled,
                                      Duration submissionRetention,
                                      int maxReviewerFeedbackLength,
                                      Duration reviewSla) {

    public ResumeCopilotProperties(boolean enabled, int maxJobDescriptionLength, int maxResumeLength,
                                   int maxCriteriaCount, int maxEvidenceCount, Duration reviewTokenTtl,
                                   boolean protectedAttributeGuardEnabled,
                                   Duration submissionRetention,
                                   int maxReviewerFeedbackLength) {
        this(enabled, maxJobDescriptionLength, maxResumeLength, maxCriteriaCount,
                maxEvidenceCount, reviewTokenTtl, protectedAttributeGuardEnabled,
                submissionRetention, maxReviewerFeedbackLength, Duration.ofHours(24));
    }

    public ResumeCopilotProperties(boolean enabled, int maxJobDescriptionLength, int maxResumeLength,
                                   int maxCriteriaCount, int maxEvidenceCount, Duration reviewTokenTtl,
                                   boolean protectedAttributeGuardEnabled) {
        this(enabled, maxJobDescriptionLength, maxResumeLength, maxCriteriaCount,
                maxEvidenceCount, reviewTokenTtl, protectedAttributeGuardEnabled,
                Duration.ofDays(30), 4000, Duration.ofHours(24));
    }

    @ConstructorBinding
    public ResumeCopilotProperties {
        if (maxJobDescriptionLength <= 0) maxJobDescriptionLength = 12000;
        if (maxResumeLength <= 0) maxResumeLength = 20000;
        if (maxCriteriaCount <= 0) maxCriteriaCount = 30;
        if (maxEvidenceCount <= 0) maxEvidenceCount = 80;
        if (reviewTokenTtl == null || reviewTokenTtl.isZero() || reviewTokenTtl.isNegative()) {
            reviewTokenTtl = Duration.ofMinutes(30);
        }
        if (submissionRetention == null || submissionRetention.isZero() || submissionRetention.isNegative()) {
            submissionRetention = Duration.ofDays(30);
        }
        if (maxReviewerFeedbackLength <= 0) {
            maxReviewerFeedbackLength = 4000;
        }
        if (reviewSla == null || reviewSla.isZero() || reviewSla.isNegative()) {
            reviewSla = Duration.ofHours(24);
        }
    }
}
