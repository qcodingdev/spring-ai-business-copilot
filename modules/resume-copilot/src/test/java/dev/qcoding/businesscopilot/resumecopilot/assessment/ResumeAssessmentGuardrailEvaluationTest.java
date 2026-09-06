package dev.qcoding.businesscopilot.resumecopilot.assessment;

import dev.qcoding.businesscopilot.resumecopilot.ResumeModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HR-03/HR-04 固定回归：证据判断不受非岗位敏感属性影响；越界输出一律拦截。
 * 全部为确定性断言，不依赖真实模型。
 */
class ResumeAssessmentGuardrailEvaluationTest {

    private final ResumeAssessmentGuardrail guardrail = new ResumeAssessmentGuardrail();

    private ResumeModels.JobCriterion criterion() {
        return new ResumeModels.JobCriterion("C1",
                ResumeModels.CriterionCategory.SKILL,
                ResumeModels.RequirementType.REQUIRED,
                "三年以上 Java 后端开发经验",
                List.of("java"), "3 年 Java 经验");
    }

    private ResumeModels.ResumeEvidence evidence() {
        return new ResumeModels.ResumeEvidence("E1", "工作经历",
                "在某互联网公司担任 Java 后端工程师三年，负责订单系统。", 0);
    }

    private ResumeModels.AssessmentContent content(String anonymousSummary) {
        return new ResumeModels.AssessmentContent(
                anonymousSummary,
                List.of(new ResumeModels.CriterionAssessment("C1",
                        ResumeModels.MatchStatus.SUPPORTED, "简历明确写明三年 Java 后端经验。",
                        List.of("E1"))),
                List.of(),
                List.of(new ResumeModels.InterviewQuestion("C1",
                        "请介绍你负责的订单系统的技术方案。", List.of("E1"))),
                List.of("本评估仅整理证据，不构成录用结论。"));
    }

    @Test
    void rejectsRankingScoreAndHiringDecisionOutputs() {
        // HR-04：总分、排名、录用建议、通过概率一律拦截。
        for (String forbiddenSummary : List.of(
                "候选人综合总分 87 分。",
                "候选人在所有候选人中排名第一。",
                "建议录用该候选人。",
                "通过概率约 90%。")) {
            var validation = guardrail.validate(content(forbiddenSummary),
                    List.of(criterion()), List.of(evidence()));
            assertThat(validation.valid())
                    .as("越界输出必须被拦截：" + forbiddenSummary)
                    .isFalse();
        }
    }

    @Test
    void rejectsAssessmentReferencingUnknownCriterionOrEvidence() {
        // HR-04：评估只能引用本次职位标准与简历证据，越界引用必须拦截。
        var unknownCriterion = new ResumeModels.AssessmentContent(
                "候选人具备五年架构经验。",
                List.of(new ResumeModels.CriterionAssessment("C9",
                        ResumeModels.MatchStatus.SUPPORTED, "简历写明五年架构经验。",
                        List.of("E1"))),
                List.of(), List.of(), List.of());
        assertThat(guardrail.validate(unknownCriterion, List.of(criterion()), List.of(evidence())).valid())
                .isFalse();

        var unknownEvidence = new ResumeModels.AssessmentContent(
                "候选人具备五年架构经验。",
                List.of(new ResumeModels.CriterionAssessment("C1",
                        ResumeModels.MatchStatus.SUPPORTED, "简历写明五年架构经验。",
                        List.of("E9"))),
                List.of(), List.of(), List.of());
        assertThat(guardrail.validate(unknownEvidence, List.of(criterion()), List.of(evidence())).valid())
                .isFalse();
    }

    @Test
    void missingEvidenceMapsToNeedsVerificationNotInference() {
        // HR-04：缺少证据时只能标记待核实（NOT_FOUND 不得声称有证据）。
        var content = new ResumeModels.AssessmentContent(
                "简历未提供该标准的相关证据。",
                List.of(new ResumeModels.CriterionAssessment("C1",
                        ResumeModels.MatchStatus.NOT_FOUND, "简历未提及 Java 经验。",
                        List.of())),
                List.of("C1 缺少证据，待核实"), List.of(), List.of());

        var validation = guardrail.validate(content, List.of(criterion()), List.of(evidence()));
        assertThat(validation.valid()).isTrue();
        var normalized = guardrail.normalizeNotFound(content);
        assertThat(normalized.criterionAssessments().getFirst().status())
                .isEqualTo(ResumeModels.MatchStatus.NOT_FOUND);
    }

    @Test
    void sensitiveAttributeVariationForcesReviewInsteadOfChangingJudgment() {
        // HR-03：岗位相关证据完全相同；仅添加非岗位敏感属性 → 判定不再自动可信，进入复核。
        var baseline = guardrail.validate(
                content("候选人具备三年 Java 后端经验，负责订单系统。"),
                List.of(criterion()), List.of(evidence()));
        assertThat(baseline.valid()).isTrue();

        var withProtectedAttributes = guardrail.validate(
                content("候选人年龄 28 岁，性别女，具备三年 Java 后端经验，负责订单系统。"),
                List.of(criterion()), List.of(evidence()));
        assertThat(withProtectedAttributes.valid()).isFalse();
        assertThat(withProtectedAttributes.reasons()).anyMatch(reason -> reason.contains("受保护属性"));
    }

    @Test
    void jobRelevantEvidenceJudgmentStaysIdenticalAcrossIrrelevantVariations() {
        // HR-03：与岗位无关的中性信息变化不改变证据判断结果。
        var variantA = guardrail.validate(
                content("候选人具备三年 Java 后端经验，负责订单系统。"),
                List.of(criterion()), List.of(evidence()));
        var variantB = guardrail.validate(
                content("候选人具备三年 Java 后端经验，负责订单系统，住在城市 A。"),
                List.of(criterion()), List.of(evidence()));
        assertThat(variantA.valid()).isEqualTo(variantB.valid());
    }
}
