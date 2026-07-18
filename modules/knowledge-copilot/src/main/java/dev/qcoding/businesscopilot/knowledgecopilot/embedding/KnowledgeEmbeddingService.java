package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排知识分片的向量生成与 pgvector 持久化。
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
     * 为文档的全部分片生成并持久化向量。
     *
     * <p>在调用本方法前，应确保 chunks 已入库且都有数据库生成的 id。
     * 若同一文档已有旧 embedding（如重建索引场景），先删除再写入。</p>
     *
     * @param documentId 所属文档编号
     * @param chunks     待索引的已持久化分片，每个分片必须有编号
     * @return 索引操作摘要
     * @throws dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException 未配置向量模型时抛出
     * @throws BusinessException                                              维度不匹配或模型调用失败时抛出
     */
    // 外部模型调用不能占用数据库事务；失败时索引任务会保留可重试状态并禁用文档。
    public EmbeddingIndexResult indexChunks(Long documentId, List<KnowledgeChunk> chunks) {
        // 在删除已有向量之前先确认模型可用；文本检索降级不能破坏可恢复的历史向量。
        if (!aiEmbeddingService.isModelEnabled()) {
            throw new AiModelNotEnabledException(
                    "未配置可用的 Embedding 模型，将由索引任务降级为文本检索。");
        }

        // 1. 删除旧 embedding（重建索引场景）
        int deleted = embeddingRepository.deleteByDocumentId(documentId);
        if (deleted > 0) {
            log.info("已删除文档原有向量：documentId={}，数量={}", documentId, deleted);
        }

        if (chunks.isEmpty()) {
            log.info("文档没有可索引分片：documentId={}", documentId);
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
                                "向量维度不匹配：模型“%s”返回 %d 维，当前配置要求 %d 维。"
                                        + "请将 SPRING_AI_OPENAI_EMBEDDING_DIMENSION 调整为模型实际输出维度，"
                                        + "然后重新索引已有文档。",
                                modelName, vector.length, configuredDimension));
            }

            embeddings.add(new KnowledgeChunkEmbedding(
                    null, chunk.id(), modelName, vector, null));
        }

        // 4. 批量持久化
        embeddingRepository.saveAll(embeddings);
        log.info("文档向量写入完成：数量={}，documentId={}，model={}，dim={}",
                embeddings.size(), documentId, modelName, configuredDimension);

        return new EmbeddingIndexResult(documentId, embeddings.size(), modelName, configuredDimension);
    }

    /**
     * 重建文档索引：删除旧向量并按现有分片重新生成。
     *
     * @param documentId 待重建索引的文档编号
     * @return 重建索引操作摘要
     */
    public EmbeddingIndexResult reindex(Long documentId, List<KnowledgeChunk> chunks) {
        return indexChunks(documentId, chunks);
    }
}
