package dev.qcoding.businesscopilot.supportcopilot.knowledge;

/**
 * Interface for retrieving knowledge evidence relevant to a support ticket.
 *
 * <p>Support Copilot 定义窄接口用于知识检索。具体实现由 app 层装配，
 * 可以适配 Knowledge Copilot 的检索服务，也可以使用独立的检索源。
 * Knowledge Copilot 不反向依赖此接口。</p>
 */
public interface SupportKnowledgeRetriever {

    /**
     * Retrieve relevant knowledge evidence for the given query.
     *
     * @param query the search query derived from ticket analysis
     * @return retrieval result with evidence and status
     */
    SupportKnowledgeResult retrieve(SupportKnowledgeQuery query);
}
