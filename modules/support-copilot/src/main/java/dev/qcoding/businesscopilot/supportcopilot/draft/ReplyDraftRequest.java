package dev.qcoding.businesscopilot.supportcopilot.draft;

import jakarta.validation.constraints.NotNull;

/**
 * 为已分类工单生成回复草稿的请求。
 *
 * <p>回复草稿生成请求。由 controller 在分类和知识检索完成后构建。</p>
 *
 * @param ticketId          关联的工单编号
 * @param customerMessage   已脱敏的客户消息
 * @param category          分类类别
 * @param sentiment         识别出的情绪
 * @param urgency           紧急程度
 * @param summary           工单摘要
 * @param needsHuman        分类阶段是否已标记需要人工处理
 * @param knowledgeEvidence 提供给提示词的知识证据文本
 * @param evidenceChunkIds  逗号分隔的可用证据分块编号
 */
public record ReplyDraftRequest(
        @NotNull(message = "工单编号不能为空。") Long ticketId,
        String customerMessage,
        dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory category,
        dev.qcoding.businesscopilot.supportcopilot.classification.TicketSentiment sentiment,
        dev.qcoding.businesscopilot.supportcopilot.classification.TicketUrgency urgency,
        String summary,
        boolean needsHuman,
        String knowledgeEvidence,
        String evidenceChunkIds,
        String knowledgeVersionIds) {

    public ReplyDraftRequest(
            Long ticketId, String customerMessage,
            dev.qcoding.businesscopilot.supportcopilot.classification.TicketCategory category,
            dev.qcoding.businesscopilot.supportcopilot.classification.TicketSentiment sentiment,
            dev.qcoding.businesscopilot.supportcopilot.classification.TicketUrgency urgency,
            String summary, boolean needsHuman, String knowledgeEvidence,
            String evidenceChunkIds) {
        this(ticketId, customerMessage, category, sentiment, urgency, summary,
                needsHuman, knowledgeEvidence, evidenceChunkIds, null);
    }
}
