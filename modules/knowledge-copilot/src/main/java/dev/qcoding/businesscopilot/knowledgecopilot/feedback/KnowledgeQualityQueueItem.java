package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import java.time.Instant;

/** 管理员和审计员可见的知识质量复核项。 */
public record KnowledgeQualityQueueItem(
        Long answerId,
        String requestId,
        String question,
        String answerStatus,
        String refusalReason,
        KnowledgeFeedbackRating rating,
        KnowledgeFeedbackReason feedbackReason,
        String comment,
        Instant answerCreatedAt,
        Instant feedbackUpdatedAt,
        long issueVersion,
        Instant issueUpdatedAt) {
}
