package dev.qcoding.businesscopilot.supportcopilot.draft;

import java.util.List;

/**
 * Structured output from the LLM for reply draft generation.
 *
 * <p>模型输出的 JSON 反序列化目标。由 ReplyDraftService 使用。</p>
 */
public record LlmReplyDraftOutput(
        String replyText,
        String riskLevel,
        List<String> riskReasons,
        List<LlmCitation> citations,
        boolean needsHuman) {

    public record LlmCitation(
            String chunkId,
            String reason) {
    }
}
