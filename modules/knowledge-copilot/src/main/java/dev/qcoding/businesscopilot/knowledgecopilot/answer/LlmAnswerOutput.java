package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The structured JSON output the LLM must produce when called for answer generation.
 *
 * <p>LLM 生成答案时必须输出的 JSON 结构。包含状态、答案正文、引用列表和警告。
 * 由 {@link JsonOutputParser} 从模型原始输出中解析。</p>
 */
record LlmAnswerOutput(
        @JsonProperty(required = true)
        String status,

        String answer,

        List<CitationEntry> citations,

        List<String> warnings) {

    record CitationEntry(
            Long chunkId,
            String excerpt) {
    }
}
