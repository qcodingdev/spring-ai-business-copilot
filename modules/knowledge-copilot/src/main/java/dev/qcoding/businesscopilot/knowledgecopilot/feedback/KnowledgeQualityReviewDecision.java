package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

/** 知识质量问题的人工处置结论。 */
public enum KnowledgeQualityReviewDecision {
    /** 问题已通过补充说明或其他人工方式处理。 */
    RESOLVED,
    /** 经复核不需要继续处理。 */
    DISMISSED,
    /** 需要进入知识资料维护流程。 */
    KNOWLEDGE_UPDATE_REQUIRED
}
