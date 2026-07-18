package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import java.util.List;

/**
 * Response from the knowledge copilot question-answering pipeline.
 *
 * <p>知识问答响应。包含处理状态、答案文本、引用列表、警告信息和模型名。</p>
 *
 * @param status    processing status — ANSWERED, NO_EVIDENCE, or REJECTED
 * @param answer    the generated answer text; null when status is not ANSWERED
 * @param citations citations backing the answer; empty or null when status is not ANSWERED
 * @param warnings  optional warnings about the answer quality or process
 * @param modelName name of the chat model used to generate the answer
 */
public record KnowledgeAnswerResponse(
        KnowledgeAnswerStatus status,
        String answer,
        List<KnowledgeCitation> citations,
        List<String> warnings,
        String modelName,
        KnowledgeAnswerMetrics metrics) {

    public KnowledgeAnswerResponse(KnowledgeAnswerStatus status, String answer,
                                   List<KnowledgeCitation> citations, List<String> warnings,
                                   String modelName) {
        this(status, answer, citations, warnings, modelName, null);
    }
}
