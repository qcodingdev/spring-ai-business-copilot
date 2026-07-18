package dev.qcoding.businesscopilot.knowledgecopilot.retrieval;

import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunkRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.embedding.KnowledgeEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过混合检索召回相关知识分片。
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
     * 为指定问题召回前 K 个相关分片。
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
     * @param question 用户自然语言问题
     * @return 按相关度排序的分片列表；没有结果达到阈值时返回空列表
     */
    public List<RetrievedKnowledgeChunk> retrieve(String question) {
        int topK = properties.topK();
        double minSimilarity = properties.minSimilarity();

        log.debug("开始知识检索：topK={}，minSimilarity={}", topK, minSimilarity);

        Map<Long, RankedResult> fused = new HashMap<>();
        List<KnowledgeChunkRepository.TextSearchResult> textResults =
                chunkRepository.findByTextSearch(question, topK * 2);
        for (int index = 0; index < textResults.size(); index++) {
            var item = textResults.get(index);
            fused.computeIfAbsent(item.chunkId(), ignored -> new RankedResult())
                    .addText(index + 1, item.rank());
        }

        List<KnowledgeChunkRepository.TextSearchResult> keywordResults =
                chunkRepository.findByKeywordSearch(KnowledgeQueryTerms.extract(question), topK * 2);
        for (int index = 0; index < keywordResults.size(); index++) {
            var item = keywordResults.get(index);
            fused.computeIfAbsent(item.chunkId(), ignored -> new RankedResult())
                    .addKeyword(index + 1, item.rank());
        }

        String embeddingModel = properties.embeddingModelName();
        try {
            float[] questionVector = aiEmbeddingService.embed(question);
            List<KnowledgeEmbeddingRepository.SimilaritySearchResult> vectorResults =
                    embeddingRepository.findSimilarChunks(
                            questionVector, embeddingModel, topK * 2, minSimilarity);
            for (int index = 0; index < vectorResults.size(); index++) {
                var item = vectorResults.get(index);
                fused.computeIfAbsent(item.chunkId(), ignored -> new RankedResult())
                        .addVector(index + 1, item.similarity());
            }
        } catch (dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException ex) {
            log.info("向量模型未启用，仅使用 PostgreSQL 文本检索");
        }

        if (fused.isEmpty()) {
            log.info("没有知识分片达到混合检索阈值");
            return List.of();
        }

        List<Map.Entry<Long, RankedResult>> ranked = fused.entrySet().stream()
                .sorted(Map.Entry.<Long, RankedResult>comparingByValue(
                        Comparator.comparingDouble(RankedResult::fusedScore)).reversed())
                .limit(topK)
                .toList();

        List<RetrievedKnowledgeChunk> retrieved = new ArrayList<>();
        for (Map.Entry<Long, RankedResult> result : ranked) {
            chunkRepository.findById(result.getKey()).ifPresentOrElse(
                    chunk -> retrieved.add(new RetrievedKnowledgeChunk(
                            chunk, result.getValue().bestScore(), embeddingModel)),
                    () -> log.warn("相似度检索命中分片 {}，但 knowledge_chunks 表中不存在该分片",
                            result.getKey()));
        }

        log.info("混合检索完成：命中分片数={}，topK={}", retrieved.size(), topK);

        return retrieved;
    }

    public String embeddingModelName() {
        return properties.embeddingModelName();
    }

    private static final class RankedResult {
        private double fusedScore;
        private double bestScore;

        void addText(int rank, double score) {
            fusedScore += 1.0d / (60 + rank);
            bestScore = Math.max(bestScore, score);
        }

        void addVector(int rank, double score) {
            fusedScore += 1.0d / (60 + rank);
            bestScore = Math.max(bestScore, score);
        }

        void addKeyword(int rank, double score) {
            fusedScore += 1.0d / (60 + rank);
            bestScore = Math.max(bestScore, score);
        }

        double fusedScore() {
            return fusedScore;
        }

        double bestScore() {
            return bestScore;
        }
    }
}
