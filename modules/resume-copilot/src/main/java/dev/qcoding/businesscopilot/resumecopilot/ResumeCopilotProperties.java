package dev.qcoding.businesscopilot.resumecopilot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "business-copilot.resume-copilot")
public record ResumeCopilotProperties(boolean enabled, int maxJobDescriptionLength, int maxResumeLength,
                                      int maxCriteriaCount, int maxEvidenceCount, Duration reviewTokenTtl,
                                      boolean protectedAttributeGuardEnabled) {
    public ResumeCopilotProperties {
        if (maxJobDescriptionLength <= 0) maxJobDescriptionLength = 12000;
        if (maxResumeLength <= 0) maxResumeLength = 20000;
        if (maxCriteriaCount <= 0) maxCriteriaCount = 30;
        if (maxEvidenceCount <= 0) maxEvidenceCount = 80;
        if (reviewTokenTtl == null || reviewTokenTtl.isZero() || reviewTokenTtl.isNegative()) {
            reviewTokenTtl = Duration.ofMinutes(30);
        }
    }
}
