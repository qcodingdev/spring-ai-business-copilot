package dev.qcoding.businesscopilot.supportcopilot.draft;

import jakarta.validation.constraints.NotNull;

/**
 * Request to generate a reply draft for a classified ticket.
 *
 * <p>回复草稿生成请求。由 controller 在分类和知识检索完成后构建。</p>
 *
 * @param ticketId         associated ticket ID
 * @param customerMessage  masked customer message
 * @param category         classification category
 * @param sentiment        detected sentiment
 * @param urgency          urgency level
 * @param summary          ticket summary
 * @param needsHuman       whether classification already flagged for human
 * @param knowledgeEvidence serialized knowledge evidence text for the prompt
 * @param evidenceChunkIds  comma-separated chunk IDs available as evidence
 */
public record ReplyDraftRequest(
        @NotNull Long ticketId,
        String customerMessage,
        String category,
        String sentiment,
        String urgency,
        String summary,
        boolean needsHuman,
        String knowledgeEvidence,
        String evidenceChunkIds) {
}
