package dev.qcoding.businesscopilot.supportcopilot.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback implementation of {@link SupportKnowledgeRetriever} that returns no results.
 *
 * <p>当 Knowledge Copilot 检索能力不可用时使用此实现，返回空结果和清晰的原因说明。
 * 后续回复生成阶段会因此降级为 needsHuman 或要求补充知识。</p>
 */
public class FallbackSupportKnowledgeRetriever implements SupportKnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(FallbackSupportKnowledgeRetriever.class);

    @Override
    public SupportKnowledgeResult retrieve(SupportKnowledgeQuery query) {
        log.warn("知识检索不可用，当前使用兜底检索器。"
                + "Ticket category={}, query summary={}", query.category(), query.summary());
        return SupportKnowledgeResult.noResults("知识库检索不可用：未配置 Knowledge Copilot 或检索服务");
    }
}
