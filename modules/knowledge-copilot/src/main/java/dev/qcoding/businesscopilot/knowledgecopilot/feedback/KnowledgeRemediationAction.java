package dev.qcoding.businesscopilot.knowledgecopilot.feedback;

/** 质量问题复核后的后续处置动作。 */
public enum KnowledgeRemediationAction {
    NONE,
    REINDEX_SOURCE,
    UPDATE_KNOWLEDGE,
    ADJUST_POLICY,
    FOLLOW_UP_WITH_REQUESTER
}
