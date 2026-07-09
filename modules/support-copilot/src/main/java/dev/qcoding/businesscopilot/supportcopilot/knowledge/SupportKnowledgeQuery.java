package dev.qcoding.businesscopilot.supportcopilot.knowledge;

import java.util.List;

/**
 * Query for knowledge evidence retrieval in the Support Copilot context.
 *
 * <p>知识依据检索查询。包含工单的客户消息、分类和摘要，用于构建检索语句。</p>
 *
 * @param customerMessage masked customer message text
 * @param category        ticket classification category
 * @param summary         ticket issue summary
 */
public record SupportKnowledgeQuery(
        String customerMessage,
        String category,
        String summary) {
}
