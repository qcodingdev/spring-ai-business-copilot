package dev.qcoding.businesscopilot.knowledgecopilot.answer;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to ask a question against the knowledge base.
 *
 * <p>知识库问答请求。只包含问题文本；不做多轮对话记忆，每次调用独立。</p>
 *
 * @param question the user's natural language question
 */
public record KnowledgeAnswerRequest(
        @NotBlank(message = "问题不能为空")
        String question,
        String category) {

    public KnowledgeAnswerRequest(String question) {
        this(question, null);
    }
}
