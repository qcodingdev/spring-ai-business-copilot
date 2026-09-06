package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerSupportGuardrailServiceTest {

    private final AnswerSupportGuardrailService service = new AnswerSupportGuardrailService();

    private static RetrievedKnowledgeChunk chunk(long id, String content) {
        return new RetrievedKnowledgeChunk(
                new KnowledgeChunk(id, 1L, "Section", 0, content, content, 10, null), 0.9, "model");
    }

    @Test
    void acceptsAnswerWhoseDetailsAppearInCitedEvidence() {
        var assessment = service.assess(
                "年假为 15 天，需在 2026-08-28 前提交申请。",
                List.of(new KnowledgeCitation(1L, "员工年假 15 天，申请截止 2026-08-28。")),
                List.of(chunk(1L, "员工年假 15 天，申请截止 2026-08-28。")));

        assertThat(assessment.supported()).as(assessment.violations().toString()).isTrue();
        assertThat(assessment.violations()).isEmpty();
        assertThat(assessment.citationValidity()).isEqualTo(1.0d);
        assertThat(assessment.excerptGroundedness()).isEqualTo(1.0d);
    }

    @Test
    void rejectsFabricatedNumbersAbsentFromCitedEvidence() {
        // KNOW-02：答案把证据中的 15 天夸大为 30 天，数字无法被证据支撑 → 拒绝。
        var assessment = service.assess(
                "员工年假为 30 天。",
                List.of(new KnowledgeCitation(1L, "员工年假 15 天。")),
                List.of(chunk(1L, "员工年假 15 天。")));

        assertThat(assessment.supported()).isFalse();
        assertThat(assessment.violations()).singleElement().asString().contains("30");
    }

    @Test
    void ignoresSingleDigitsToAvoidChineseNumeralFalsePositives() {
        // "5" 与证据中的"五"等价；单位数不做硬校验，避免中文数字误拒。
        var assessment = service.assess(
                "年假为 5 天。",
                List.of(new KnowledgeCitation(1L, "入职满一年享受五天带薪年假。")),
                List.of(chunk(1L, "入职满一年享受五天带薪年假。")));

        assertThat(assessment.supported()).isTrue();
    }

    @Test
    void rejectsAnswerWithoutCitations() {
        var assessment = service.assess("答案文本", List.of(), List.of(chunk(1L, "证据")));

        assertThat(assessment.supported()).isFalse();
        assertThat(assessment.citationValidity()).isZero();
    }

    @Test
    void rejectsInventedCausalClaimEvenWithoutNumbers() {
        var assessment = service.assess(
                "系统故障是供应商违规操作导致的。",
                List.of(new KnowledgeCitation(1L, "系统故障仍在调查中。")),
                List.of(chunk(1L, "系统故障仍在调查中，当前没有确认根因。")));

        assertThat(assessment.supported()).isFalse();
        assertThat(assessment.violations()).anyMatch(item -> item.contains("陈述缺少"));
    }

    @Test
    void matchesEvidenceAcrossLineBreaksAndWhitespace() {
        var assessment = service.assess(
                "报销限额为 3500 元。",
                List.of(new KnowledgeCitation(1L, "单次报销限额\n3500 元。")),
                List.of(chunk(1L, "单次报销限额\n3500 元。")));

        assertThat(assessment.supported()).isTrue();
    }

    @Test
    void preservesEnglishWordBoundariesForGroundedSingularPluralParaphrase() {
        var assessment = service.assess(
                "Fictional customers may request a refund within 30 days after delivery.",
                List.of(new KnowledgeCitation(1L,
                        "A fictional customer may request a refund within 30 days after delivery.")),
                List.of(chunk(1L,
                        "A fictional customer may request a refund within 30 days after delivery.")));

        assertThat(assessment.supported()).as(assessment.violations().toString()).isTrue();
    }
}
