package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Retrieves relevant knowledge chunks via vector similarity search.
 *
 * <p>知识检索服务。将用户问题向量化，在 pgvector 中执行余弦相似度检索，
 * 只返回 enabled=true 的文档中相似度超过 minSimilarity 阈值的 chunk。
 * 同时加载 chunk 的完整内容（content、sectionTitle 等）供答案生成使用。</p>
 *
 * <p>只检索 similarity >= minSimilarity 的 chunk，低于阈值的不进入上下文。
 * 如果没有 chunk 达到阈值，返回空列表，由上游 {@code KnowledgeAnswerService} 处理 NO_EVIDENCE。</p>
 */
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    private final AiEmbeddingService aiEmbeddingService;
    private final KnowledgeEmbeddingRepository embeddingRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeCopilotProperties properties;

    public KnowledgeRetrievalService(AiEmbeddingService aiEmbeddingService,
                                     KnowledgeEmbeddingRepository embeddingRepository,
                                     KnowledgeChunkRepository chunkRepository,
                                     KnowledgeCopilotProperties properties) {
        this.aiEmbeddingService = aiEmbeddingService;
        this.embeddingRepository = embeddingRepository;
        this.chunkRepository = chunkRepository;
        this.properties = properties;
    }

    /**
     * Retrieve top-K relevant chunks for the given question.
     *
     * <p>流程：
     * <ol>
     *   <li>问题向量化（调用 embedding model）</li>
     *   <li>在 enabled 文档中检索 topK 最相似的 chunk</li>
     *   <li>加载每个匹配 chunk 的完整内容</li>
     *   <li>返回 {@link RetrievedKnowledgeChunk} 列表（相似度降序）</li>
     * </ol>
     * 如果没有 chunk 满足 minSimilarity 阈值，返回空列表。</p>
     *
     * @param question the user's natural language question
     * @return similarity-sorted list of retrieved chunks with scores; empty if none meet the threshold
     */
    public List<RetrievedKnowledgeChunk> retrieve(String question) {
        int topK = properties.topK();
        double minSimilarity = properties.minSimilarity();

        log.debug("Retrieving topK={} chunks with minSimilarity={}", topK, minSimilarity);

        // 1. 问题向量化
        float[] questionVector = aiEmbeddingService.embed(question);
        String embeddingModel = aiEmbeddingService.modelName();
        log.debug("Question embedded with model={}, dim={}", embeddingModel, questionVector.length);

        // 2. pgvector 相似度检索 (只检索 enabled 文档)
        List<KnowledgeEmbeddingRepository.SimilaritySearchResult> results =
                embeddingRepository.findSimilarChunks(questionVector, topK, minSimilarity);

        if (results.isEmpty()) {
            log.info("No chunks met similarity threshold {} for question: {}",
                    minSimilarity, truncate(question));
            return List.of();
        }

        // 3. 加载 chunk 完整内容
        List<RetrievedKnowledgeChunk> retrieved = new ArrayList<>();
        for (KnowledgeEmbeddingRepository.SimilaritySearchResult result : results) {
            chunkRepository.findById(result.chunkId()).ifPresentOrElse(
                    chunk -> retrieved.add(new RetrievedKnowledgeChunk(
                            chunk, result.similarity(), embeddingModel)),
                    () -> log.warn("Chunk {} found in similarity search but missing in knowledge_chunks table",
                            result.chunkId()));
        }

        log.info("Retrieved {} chunks (topK={}, minSimilarity={}) for question: {}",
                retrieved.size(), topK, minSimilarity, truncate(question));

        return retrieved;
    }

    public String embeddingModelName() {
        return aiEmbeddingService.modelName();
    }

    private static String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
