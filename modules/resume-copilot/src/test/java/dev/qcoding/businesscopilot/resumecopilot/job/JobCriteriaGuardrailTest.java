package dev.qcoding.businesscopilot.resumecopilot.job;

import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;
import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobCriteriaGuardrailTest {
    private final JobCriteriaGuardrail guardrail = new JobCriteriaGuardrail(
            new ResumeCopilotProperties(true, 12000, 20000, 30, 80, Duration.ofMinutes(30), true));

    @Test
    void acceptsCriterionTraceableToJobDescription() {
        var criterion = new ResumeModels.JobCriterion("criterion-1", ResumeModels.CriterionCategory.SKILL,
                ResumeModels.RequirementType.REQUIRED, "Spring Boot experience", List.of("Spring Boot"),
                "Spring Boot experience");

        assertThat(guardrail.validate(List.of(criterion), "Required: Spring Boot experience").valid()).isTrue();
    }

    @Test
    void rejectsInventedOrProtectedCriterion() {
        var criterion = new ResumeModels.JobCriterion("criterion-1", ResumeModels.CriterionCategory.OTHER,
                ResumeModels.RequirementType.REQUIRED, "年轻且稳定", List.of(), "年轻且稳定");

        var result = guardrail.validate(List.of(criterion), "Required: Java experience");

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons()).hasSizeGreaterThanOrEqualTo(2);
    }
}
