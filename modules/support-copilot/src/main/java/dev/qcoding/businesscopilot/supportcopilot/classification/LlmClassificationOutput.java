package dev.qcoding.businesscopilot.supportcopilot.classification;

import java.util.List;

/**
 * Structured output from the LLM for ticket classification.
 *
 * <p>模型输出的 JSON 反序列化目标。由 TicketClassificationService 使用。</p>
 */
public record LlmClassificationOutput(
        String category,
        String sentiment,
        String urgency,
        String summary,
        boolean needsHuman,
        List<String> reasons) {
}
