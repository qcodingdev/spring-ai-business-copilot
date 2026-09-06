package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

import java.time.Instant;

/**
 * 管理员和复核员可见的点赞/点踩明细。
 *
 * <p>不返回操作者身份，避免把反馈列表变成个人行为追踪；保留问答上下文和
 * 反馈时间，便于质量趋势分析及人工复核。</p>
 */
public record KnowledgeFeedbackHistoryItem(
        Long feedbackId,
        Long answerId,
        String requestId,
        String question,
        String answerPreview,
        KnowledgeFeedbackRating rating,
        KnowledgeFeedbackReason feedbackReason,
        String comment,
        Instant feedbackCreatedAt,
        Instant feedbackUpdatedAt) {
}
