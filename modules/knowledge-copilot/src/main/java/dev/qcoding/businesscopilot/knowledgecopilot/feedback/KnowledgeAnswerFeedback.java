package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import java.time.Instant;

/** 已持久化的知识问答反馈。 */
public record KnowledgeAnswerFeedback(
        Long id,
        Long answerId,
        String actorId,
        KnowledgeFeedbackRating rating,
        KnowledgeFeedbackReason reason,
        String comment,
        Instant createdAt,
        Instant updatedAt) {
}
