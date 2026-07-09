package dev.qcoding.businesscopilot.supportcopilot.knowledge;

import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.KnowledgeRetrievalService;
import dev.qcoding.businesscopilot.knowledgecopilot.retrieval.RetrievedKnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter that delegates knowledge retrieval to Knowledge Copilot's {@link KnowledgeRetrievalService}.
 *
 * <p>通过 Knowledge Copilot 的检索能力为 Support Copilot 提供知识依据。
 * 此实现直接依赖 knowledge-copilot 模块，在 app 层装配。
 * Knowledge Copilot 不反向依赖 Support Copilot。</p>
 *
 * <p>只检索 enabled=true 的文档中的 chunk。</p>
 */
public class KnowledgeCopilotSupportKnowledgeRetriever implements SupportKnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCopilotSupportKnowledgeRetriever.class);

    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeDocumentRepository documentRepository;

    public KnowledgeCopilotSupportKnowledgeRetriever(KnowledgeRetrievalService retrievalService,
                                                      KnowledgeDocumentRepository documentRepository) {
        this.retrievalService = retrievalService;
        this.documentRepository = documentRepository;
    }

    @Override
    public SupportKnowledgeResult retrieve(SupportKnowledgeQuery query) {
        // 组合检索查询：使用客户消息、摘要和分类构建检索文本
        String searchQuery = buildSearchQuery(query);
        log.debug("Retrieving knowledge evidence for ticket: category={}, queryLength={}",
                query.category(), searchQuery.length());

        List<RetrievedKnowledgeChunk> chunks = retrievalService.retrieve(searchQuery);

        if (chunks.isEmpty()) {
            return SupportKnowledgeResult.noResults("未检索到与工单相关的知识依据，建议转人工处理或补充知识库内容");
        }

        List<SupportKnowledgeEvidence> evidence = new ArrayList<>();
        for (RetrievedKnowledgeChunk rc : chunks) {
            var chunk = rc.chunk();
            // chunk 来自 enabled 文档（retrievalService 只检索 enabled 文档的 chunk）
            var doc = documentRepository.findById(chunk.documentId());
            String sourceTitle = doc.map(d -> d.title()).orElse("未知文档");

            evidence.add(new SupportKnowledgeEvidence(
                    sourceTitle,
                    chunk.sectionTitle(),
                    chunk.content() != null && chunk.content().length() > 200
                            ? chunk.content().substring(0, 200) + "..."
                            : chunk.content(),
                    String.valueOf(chunk.id()),
                    rc.similarity()));
        }

        log.info("Retrieved {} knowledge evidence items for ticket category={}",
                evidence.size(), query.category());

        return SupportKnowledgeResult.of(evidence);
    }

    private String buildSearchQuery(SupportKnowledgeQuery query) {
        StringBuilder sb = new StringBuilder();
        if (query.summary() != null && !query.summary().isBlank()) {
            sb.append(query.summary());
        }
        if (query.customerMessage() != null && !query.customerMessage().isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(query.customerMessage());
        }
        if (query.category() != null && !query.category().isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(query.category());
        }
        return sb.toString();
    }
}
