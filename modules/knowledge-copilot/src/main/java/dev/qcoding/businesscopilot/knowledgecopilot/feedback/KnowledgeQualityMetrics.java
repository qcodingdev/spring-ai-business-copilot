package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

/** 不包含业务原文的低基数知识质量统计。 */
public record KnowledgeQualityMetrics(
        long feedbackCount,
        long helpfulCount,
        long notHelpfulCount,
        long pendingReviewCount,
        long resolvedCount,
        long dismissedCount,
        long knowledgeUpdateRequiredCount) {
}
