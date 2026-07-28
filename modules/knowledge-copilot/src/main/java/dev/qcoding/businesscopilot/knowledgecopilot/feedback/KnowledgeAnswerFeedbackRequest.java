package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 提交或更新知识问答反馈的请求。 */
public record KnowledgeAnswerFeedbackRequest(
        @NotNull KnowledgeFeedbackRating rating,
        KnowledgeFeedbackReason reason,
        @Size(max = 1000) String comment) {
}
