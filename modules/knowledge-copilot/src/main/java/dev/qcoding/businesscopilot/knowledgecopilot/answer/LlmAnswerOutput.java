package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import java.util.List;

/**
 * The structured JSON output the LLM must produce when called for answer generation.
 *
 * <p>LLM 生成答案时必须输出的 JSON 结构。包含状态、答案正文、引用列表和警告。
 * 由 Spring AI structured output API 从模型原始输出中映射。</p>
 */
record LlmAnswerOutput(
        String status,

        String answer,

        List<CitationEntry> citations,

        List<String> warnings) {

    record CitationEntry(
            Long chunkId,
            String excerpt) {
    }
}
