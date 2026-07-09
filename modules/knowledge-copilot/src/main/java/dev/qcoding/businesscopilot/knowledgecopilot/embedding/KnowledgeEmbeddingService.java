package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates embedding generation and pgvector persistence for knowledge chunks.
 *
 * <p>知识分片 embedding 服务。为文档分片生成 embedding 向量并持久化到 pgvector。
 * 支持首次索引和重建索引（先删除旧 embedding 再写入新 embedding）。
 * 每个 chunk 生成一条 {@link KnowledgeChunkEmbedding} 记录，
 * embedding_model 字段记录实际使用的模型名称，便于审计和排查模型切换问题。</p>
 *
 * <p>调用方（如 {@code DocumentUploadService}）持有本服务引用，在分片入库后调用
 * {@link #indexChunks(Long, List)} 同步生成 embedding。若 embedding 模型不可用，
 * 调用方应捕获 {@code AiModelNotEnabledException} 并决定是否阻止业务流程。</p>
 */
public class KnowledgeEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingService.class);

    private final AiEmbeddingService aiEmbeddingService;
    private final KnowledgeEmbeddingRepository embeddingRepository;
    private final KnowledgeCopilotProperties properties;

    public KnowledgeEmbeddingService(AiEmbeddingService aiEmbeddingService,
                                      KnowledgeEmbeddingRepository embeddingRepository,
                                      KnowledgeCopilotProperties properties) {
        this.aiEmbeddingService = aiEmbeddingService;
        this.embeddingRepository = embeddingRepository;
        this.properties = properties;
    }

    /**
     * Generate and persist embeddings for all chunks of a document.
     *
     * <p>在调用本方法前，应确保 chunks 已入库且都有数据库生成的 id。
     * 若同一文档已有旧 embedding（如重建索引场景），先删除再写入。</p>
     *
     * @param documentId the owning document ID
     * @param chunks     the list of persisted chunks to index, each with a non-null id
     * @return summary of the indexing operation
     * @throws dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException if no embedding model is configured
     * @throws BusinessException                                              on dimension mismatch or model invocation failure
     */
    @Transactional
    public EmbeddingIndexResult indexChunks(Long documentId, List<KnowledgeChunk> chunks) {
        // 1. 删除旧 embedding（重建索引场景）
        int deleted = embeddingRepository.deleteByDocumentId(documentId);
        if (deleted > 0) {
            log.info("Deleted {} existing embeddings for documentId={}", deleted, documentId);
        }

        if (chunks.isEmpty()) {
            log.info("No chunks to index for documentId={}", documentId);
            return new EmbeddingIndexResult(documentId, 0,
                    properties.embeddingModelName(), properties.embeddingDimension());
        }

        // 2. 为每个 chunk 生成 embedding 并验证维度
        String modelName = properties.embeddingModelName();
        int configuredDimension = properties.embeddingDimension();

        List<KnowledgeChunkEmbedding> embeddings = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            float[] vector = aiEmbeddingService.embed(chunk.content());

            // 3. 维度不匹配时给出清晰错误，指导修正方向
            if (vector.length != configuredDimension) {
                throw new BusinessException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        String.format(
                                "Embedding dimension mismatch: model '%s' returned %d dimensions, "
                                        + "database expects %d. "
                                        + "Update business-copilot.knowledge.embedding-dimension "
                                        + "or rebuild the knowledge_chunk_embeddings table with ALTER COLUMN.",
                                modelName, vector.length, configuredDimension));
            }

            embeddings.add(new KnowledgeChunkEmbedding(
                    null, chunk.id(), modelName, vector, null));
        }

        // 4. 批量持久化
        embeddingRepository.saveAll(embeddings);
        log.info("Saved {} embeddings for documentId={} with model={} dim={}",
                embeddings.size(), documentId, modelName, configuredDimension);

        return new EmbeddingIndexResult(documentId, embeddings.size(), modelName, configuredDimension);
    }

    /**
     * Re-index a document: delete existing embeddings, re-fetch chunks, and regenerate.
     *
     * @param documentId the document to re-index
     * @return summary of the re-indexing operation
     */
    @Transactional
    public EmbeddingIndexResult reindex(Long documentId, List<KnowledgeChunk> chunks) {
        return indexChunks(documentId, chunks);
    }
}
