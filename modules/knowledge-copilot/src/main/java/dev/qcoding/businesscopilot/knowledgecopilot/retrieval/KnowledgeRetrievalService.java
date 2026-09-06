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
 *
 * <p>KNOW-05：{@link #retrieveWithGapCheck(String, String)} 在首次检索未命中任何分片时，
 * 执行一次（且仅一次）放宽阈值的补充检索；补充检索仍保持权限与生命周期过滤，
 * 仍无结果时由上游拒答，不无限重试。</p>
 *
 * <p>KNOW-03：召回命中后回表读取完整内容存在时间窗口，返回前逐片复核所属文档
 * 是否仍满足可见条件（启用、当前版本、已索引、未过期、无冲突、ACL 允许），
 * 已失效分片直接丢弃，不进入答案上下文。</p>
 */
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    /** 补充检索允许放宽的向量阈值幅度；仍不得低于 0.5，避免把弱相关内容当作证据。 */
    static final double SUPPLEMENT_MIN_SIMILARITY_FLOOR = 0.5d;
    static final double SUPPLEMENT_SIMILARITY_RELAXATION = 0.15d;

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
        return retrieve(question, null);
    }

    /** 按可选业务分类和当前请求角色检索，分类与权限都只能由服务端确定。 */
    public List<RetrievedKnowledgeChunk> retrieve(String question, String category) {
        KnowledgeAccessContext.setCategory(category);
        try {
            return retrieveWithinAccessContext(question, false);
        } finally {
            KnowledgeAccessContext.clear();
        }
    }

    /**
     * KNOW-05：带证据缺口识别的检索。
     *
     * <p>首次检索未命中任何分片时视为证据缺口，触发一次有界补充检索
     * （扩大召回上限并适度放宽向量阈值，仍受权限与生命周期过滤）。
     * 补充后仍为空则上游必须拒答或转人工，不允许继续扩大检索范围。</p>
     */
    public RetrievalOutcome retrieveWithGapCheck(String question, String category) {
        List<RetrievedKnowledgeChunk> firstPass = retrieve(question, category);
        if (!firstPass.isEmpty()) {
            return new RetrievalOutcome(firstPass, false);
        }
        log.info("首次检索未命中，执行一次有界补充检索：问题长度={}", question.length());
        KnowledgeAccessContext.setCategory(category);
        try {
            return new RetrievalOutcome(retrieveWithinAccessContext(question, true), true);
        } finally {
            KnowledgeAccessContext.clear();
        }
    }

    private List<RetrievedKnowledgeChunk> retrieveWithinAccessContext(String question) {
        return retrieveWithinAccessContext(question, false);
    }

    private List<RetrievedKnowledgeChunk> retrieveWithinAccessContext(String question, boolean supplemental) {
        int topK = properties.topK();
        double minSimilarity = supplemental
                ? Math.max(SUPPLEMENT_MIN_SIMILARITY_FLOOR,
                        properties.minSimilarity() - SUPPLEMENT_SIMILARITY_RELAXATION)
                : properties.minSimilarity();
        int recallLimit = supplemental ? topK * 3 : topK * 2;

        log.debug("开始知识检索：topK={}，minSimilarity={}，补充检索={}", topK, minSimilarity, supplemental);

        Map<Long, RankedResult> fused = new HashMap<>();
        List<KnowledgeChunkRepository.TextSearchResult> textResults =
                chunkRepository.findByTextSearch(question, recallLimit);
        for (int index = 0; index < textResults.size(); index++) {
            var item = textResults.get(index);
            fused.computeIfAbsent(item.chunkId(), ignored -> new RankedResult())
                    .addText(index + 1, item.rank());
        }

        List<KnowledgeChunkRepository.TextSearchResult> keywordResults =
                chunkRepository.findByKeywordSearch(KnowledgeQueryTerms.extract(question), recallLimit);
        for (int index = 0; index < keywordResults.size(); index++) {
            var item = keywordResults.get(index);
            fused.computeIfAbsent(item.chunkId(), ignored -> new RankedResult())
                    .addKeyword(index + 1, item.rank());
        }

        String embeddingModel = properties.embeddingModelName();
        try {
            float[] questionVector = aiEmbeddingService.embed("knowledge.question-retrieval", question);
            List<KnowledgeEmbeddingRepository.SimilaritySearchResult> vectorResults =
                    embeddingRepository.findSimilarChunks(
                            questionVector, embeddingModel, recallLimit, minSimilarity);
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
                    chunk -> {
                        // KNOW-03：回表窗口内文档可能已被停用、过期或撤销可见性，消费前必须复核。
                        if (!chunkRepository.isDocumentVisible(chunk.documentId())) {
                            log.warn("分片 {} 所属文档 {} 已不满足可见条件，丢弃失效证据",
                                    chunk.id(), chunk.documentId());
                            return;
                        }
                        retrieved.add(new RetrievedKnowledgeChunk(
                                chunk, result.getValue().bestScore(), embeddingModel));
                    },
                    () -> log.warn("相似度检索命中分片 {}，但 knowledge_chunks 表中不存在该分片",
                            result.getKey()));
        }

        log.info("混合检索完成：命中分片数={}，topK={}，补充检索={}", retrieved.size(), topK, supplemental);

        return List.copyOf(retrieved);
    }

    public String embeddingModelName() {
        return properties.embeddingModelName();
    }

    /** 一次检索的结果：证据列表与是否动用了补充检索（用于审计与评测区分召回质量）。 */
    public record RetrievalOutcome(List<RetrievedKnowledgeChunk> evidence, boolean supplementalUsed) {

        public RetrievalOutcome {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
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
