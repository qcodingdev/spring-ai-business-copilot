package dev.qcoding.businesscopilot.knowledgecopilot.answer;

/** Deterministic grounding metrics calculated after model output validation. */
public record KnowledgeAnswerMetrics(
        double citationValidity,
        double excerptGroundedness,
        int citedChunkCount,
        int retrievedChunkCount) {
}
