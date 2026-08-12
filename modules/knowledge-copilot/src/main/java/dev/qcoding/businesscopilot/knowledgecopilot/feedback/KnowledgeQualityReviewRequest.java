package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 人工处置知识质量问题的请求；问题时间用于防止并发覆盖。 */
public record KnowledgeQualityReviewRequest(
        @NotNull KnowledgeQualityReviewDecision decision,
        @NotNull KnowledgeEvidenceAssessment evidenceAssessment,
        @NotNull KnowledgeAnswerAssessment answerAssessment,
        @NotNull KnowledgeRemediationAction remediationAction,
        @NotBlank @Size(max = 1000) String reviewNote,
        @NotNull @PositiveOrZero Long expectedIssueVersion,
        @NotNull Instant expectedIssueUpdatedAt) {
}
