package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import java.time.Instant;

/** 已持久化的知识质量人工处置结果。 */
public record KnowledgeQualityReview(
        Long id,
        Long answerId,
        KnowledgeQualityReviewDecision decision,
        KnowledgeEvidenceAssessment evidenceAssessment,
        KnowledgeAnswerAssessment answerAssessment,
        KnowledgeRemediationAction remediationAction,
        String reviewNote,
        String reviewerActorId,
        long reviewedIssueVersion,
        Instant reviewedIssueAt,
        Instant createdAt,
        Instant updatedAt) {
}
