package dev.qcoding.businesscopilot.guardrails;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceClaimGroundingTest {

    private final EvidenceClaimGrounding grounding = new EvidenceClaimGrounding();

    @Test
    void splitsEnglishClaimsAtPeriodsAndAcceptsIndividuallyGroundedFacts() {
        var assessment = grounding.assess(
                "A customer may request a refund within 30 days after delivery. "
                        + "The refund returns to the original payment method within five business days.",
                "A customer may request a refund within 30 days after delivery. "
                        + "After approval, the refund returns to the original payment method within five business days.");

        assertThat(assessment.supported()).as(assessment.unsupportedClaims().toString()).isTrue();
    }

    @Test
    void stillRejectsAnUnsupportedEnglishSentenceAmongGroundedFacts() {
        var assessment = grounding.assess(
                "A customer may request a refund within 30 days after delivery. "
                        + "All refunds are automatically approved.",
                "A customer may request a refund within 30 days after delivery. "
                        + "Every request requires human review and must never be auto-approved by AI.");

        assertThat(assessment.supported()).isFalse();
        assertThat(assessment.unsupportedClaims()).containsExactly("All refunds are automatically approved.");
    }

    @Test
    void acceptsLexicallyGroundedParaphrase() {
        assertThat(grounding.assess(
                "系统维护导致服务中断。",
                "服务中断由系统维护导致，恢复时间为下午三点。" ).supported()).isTrue();
    }

    @Test
    void rejectsInventedCausalConclusion() {
        var result = grounding.assess(
                "订单下降主要是客户流失导致。",
                "本月订单数量下降，客户满意度调查尚未完成。" );

        assertThat(result.supported()).isFalse();
        assertThat(result.unsupportedClaims()).singleElement().asString().contains("客户流失");
    }

    @Test
    void rejectsQualitativeClaimAbsentFromEvidence() {
        assertThat(grounding.assess(
                "新流程显著提高了团队效率。",
                "新流程已于周一上线，尚未完成效果评估。" ).supported()).isFalse();
    }
}
