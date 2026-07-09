package dev.qcoding.businesscopilot.supportcopilot.knowledge;

/**
 * A piece of knowledge evidence retrieved for a support ticket.
 *
 * <p>知识依据。包含文档标题、章节、片段文本和 chunk ID。
 * 所有字段用于生成回复草稿时的 citations 和引用展示。</p>
 *
 * @param sourceTitle   document title the evidence comes from
 * @param sectionTitle  section heading within the document
 * @param snippet       relevant text snippet
 * @param chunkId       knowledge chunk ID for citation tracking (null if not from Knowledge Copilot)
 * @param similarity    retrieval similarity score (0.0–1.0)
 */
public record SupportKnowledgeEvidence(
        String sourceTitle,
        String sectionTitle,
        String snippet,
        String chunkId,
        double similarity) {
}
