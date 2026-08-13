package dev.qcoding.businesscopilot.resumecopilot.assessment;

import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeAssessmentGuardrailTest {
    private final ResumeAssessmentGuardrail guardrail = new ResumeAssessmentGuardrail();
    private final List<ResumeModels.JobCriterion> criteria = List.of(new ResumeModels.JobCriterion("criterion-1",
            ResumeModels.CriterionCategory.SKILL, ResumeModels.RequirementType.REQUIRED, "Java", List.of("Java"), "Java"));
    private final List<ResumeModels.ResumeEvidence> evidence = List.of(
            new ResumeModels.ResumeEvidence("evidence-1", "EXPERIENCE", "Built Java services", 0));

    @Test
    void acceptsEvidenceGroundedAssessmentAndNormalizesNotFoundLanguage() {
        var content = new ResumeModels.AssessmentContent("匿名候选人具有后端经历", List.of(
                new ResumeModels.CriterionAssessment("criterion-1", ResumeModels.MatchStatus.SUPPORTED,
                        "简历明确提及 Java 服务", List.of("evidence-1"))), List.of(), List.of(), List.of());

        assertThat(guardrail.validate(content, criteria, evidence).valid()).isTrue();
    }

    @Test
    void blocksScoresDecisionsUnknownEvidenceAndProtectedQuestions() {
        var content = new ResumeModels.AssessmentContent("总分 90，建议录用", List.of(
                new ResumeModels.CriterionAssessment("criterion-1", ResumeModels.MatchStatus.SUPPORTED,
                        "强匹配", List.of("other-resume-evidence"))), List.of(), List.of(
                new ResumeModels.InterviewQuestion("criterion-1", "请说明年龄和婚育情况", List.of())), List.of());

        var result = guardrail.validate(content, criteria, evidence);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("自动录用决定"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("受保护属性"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("当前简历之外"));
    }

    @Test
    void rejectsDuplicateCriteriaAndNullModelItemsWithoutThrowing() {
        var assessment = new ResumeModels.CriterionAssessment("criterion-1", ResumeModels.MatchStatus.SUPPORTED,
                "Java evidence", List.of("evidence-1"));
        var content = new ResumeModels.AssessmentContent("anonymous", List.of(assessment, assessment),
                List.of(), List.of(new ResumeModels.InterviewQuestion("criterion-1", null, List.of())), List.of());

        var result = guardrail.validate(content, criteria, evidence);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("重复出现"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("内容不完整"));
    }

    @Test
    void rejectsProxyHiringLanguageAnywhereInAssessment() {
        var content = new ResumeModels.AssessmentContent("候选人毕业年份较新，适合年轻团队", List.of(
                new ResumeModels.CriterionAssessment("criterion-1", ResumeModels.MatchStatus.SUPPORTED,
                        "简历明确提及 Java 服务", List.of("evidence-1"))), List.of(), List.of(), List.of());

        assertThat(guardrail.validate(content, criteria, evidence).valid()).isFalse();
    }

    @Test
    void acceptsEnglishNarrativeOnlyWhenEnglishWasExplicitlySelected() {
        var content = new ResumeModels.AssessmentContent("Backend engineer with Java experience", List.of(
                new ResumeModels.CriterionAssessment("criterion-1", ResumeModels.MatchStatus.SUPPORTED,
                        "The resume mentions Java services", List.of("evidence-1"))),
                List.of("Cloud experience is not described"),
                List.of(new ResumeModels.InterviewQuestion("criterion-1",
                        "Please describe the Java service architecture", List.of("evidence-1"))),
                List.of("This assessment only uses resume evidence"));

        var result = guardrail.validate(content, criteria, evidence);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("简体中文"));
        try {
            dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder.set(
                    new dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext(
                            "request-en", "reviewer", java.util.Set.of("REVIEWER"), "en-US"));
            assertThat(guardrail.validate(content, criteria, evidence).valid()).isTrue();
        } finally {
            dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder.clear();
        }
    }
}
