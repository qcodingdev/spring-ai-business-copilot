package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

/** 负反馈的稳定原因，用于质量队列和评测集归因。 */
public enum KnowledgeFeedbackReason {
    MISSING_EVIDENCE,
    INCORRECT,
    OUTDATED,
    UNCLEAR,
    OTHER
}
