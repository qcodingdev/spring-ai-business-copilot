package dev.qcoding.businesscopilot.supportcopilot.knowledge;

import java.util.List;

/**
 * Result of knowledge evidence retrieval for a support ticket.
 *
 * <p>知识检索结果。包含证据列表和检索状态信息。</p>
 *
 * @param evidence  retrieved knowledge evidence (empty list if none found)
 * @param reason    human-readable explanation of retrieval status
 * @param hasResults whether any evidence was found
 */
public record SupportKnowledgeResult(
        List<SupportKnowledgeEvidence> evidence,
        String reason,
        boolean hasResults) {

    public static SupportKnowledgeResult noResults(String reason) {
        return new SupportKnowledgeResult(List.of(), reason, false);
    }

    public static SupportKnowledgeResult of(List<SupportKnowledgeEvidence> evidence) {
        return new SupportKnowledgeResult(
                evidence != null ? evidence : List.of(),
                evidence != null && !evidence.isEmpty()
                        ? "检索到 " + evidence.size() + " 条知识依据"
                        : "未检索到相关依据",
                evidence != null && !evidence.isEmpty());
    }
}
