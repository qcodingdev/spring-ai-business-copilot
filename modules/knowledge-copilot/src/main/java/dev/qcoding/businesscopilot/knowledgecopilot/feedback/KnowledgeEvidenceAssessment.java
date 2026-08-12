package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

/** 人工对当前回答证据覆盖度和有效性的判断。 */
public enum KnowledgeEvidenceAssessment {
    SUFFICIENT,
    INSUFFICIENT,
    CONFLICTING,
    OUTDATED,
    NOT_APPLICABLE
}
