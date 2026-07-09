package dev.qcoding.businesscopilot.supportcopilot.draft;

import java.util.List;

/**
 * Response from reply draft generation.
 *
 * <p>回复草稿生成响应。返回草稿内容、风险信息、引用、确认 token 和转人工建议。</p>
 *
 * @param draftId           persisted draft ID
 * @param replyText         generated reply draft text (empty if needsHuman)
 * @param riskLevel         LOW, MEDIUM, HIGH
 * @param riskReasons       reasons for the risk assessment
 * @param citations         cited knowledge evidence chunks
 * @param confirmationToken server-generated confirmation token
 * @param expiresAt         token expiry timestamp (ISO-8601)
 * @param needsHuman        whether human intervention is required
 */
public record ReplyDraftResponse(
        Long draftId,
        String replyText,
        String riskLevel,
        List<String> riskReasons,
        List<Citation> citations,
        String confirmationToken,
        String expiresAt,
        boolean needsHuman) {

    /**
     * A single citation referencing a knowledge chunk used as evidence.
     *
     * @param chunkId     ID of the referenced knowledge chunk
     * @param sourceTitle document title of the evidence
     * @param snippet     short text snippet from the evidence
     * @param reason      why this citation supports the reply
     */
    public record Citation(
            String chunkId,
            String sourceTitle,
            String snippet,
            String reason) {
    }
}
