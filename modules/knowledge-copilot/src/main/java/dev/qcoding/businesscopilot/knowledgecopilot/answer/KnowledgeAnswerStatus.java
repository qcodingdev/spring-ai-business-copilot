package dev.qcoding.businesscopilot.knowledgecopilot.answer;

/**
 * Status of a knowledge answer generation attempt.
 *
 * <p>知识问答状态：ANSWERED 表示基于检索到的上下文成功生成答案；
 * NO_EVIDENCE 表示知识库中没有足够的上下文支撑回答；
 * REJECTED 表示模型输出因 guardrail 规则被拒绝（如 citation 完整性违规等）。</p>
 */
public enum KnowledgeAnswerStatus {

    /** Answer was successfully generated with at least one valid citation. */
    ANSWERED,

    /** Insufficient or low-quality evidence retrieved — no answer produced. */
    NO_EVIDENCE,

    /** Model output violated guardrail rules (e.g., missing citations, hallucinated chunk IDs). */
    REJECTED
}
