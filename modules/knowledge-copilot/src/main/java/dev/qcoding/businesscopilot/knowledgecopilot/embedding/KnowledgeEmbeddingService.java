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
 * <p>向量生成和数据库替换分成两个阶段。外部模型调用先在事务外构造
 * {@link PreparedKnowledgeIndex}；只有索引任务租约仍有效时，生命周期服务才会在
 * 同一数据库事务内调用 {@link #persistPreparedIndex(PreparedKnowledgeIndex)}。
 * 这样既避免长事务，也能防止被取消的旧 worker 删除替代任务生成的新向量。</p>
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
     * 在事务外为文档的全部分片生成并校验向量，不修改数据库。
     *
     * @param documentId 所属文档编号
     * @param chunks     待索引的已持久化分片，每个分片必须有编号
     * @return 可在租约校验后提交的完整向量集合
     * @throws AiModelNotEnabledException 未配置向量模型时抛出
     * @throws BusinessException          维度不匹配或模型调用失败时抛出
     */
    public PreparedKnowledgeIndex prepareIndex(Long documentId, List<KnowledgeChunk> chunks) {
        if (!aiEmbeddingService.isModelEnabled()) {
            throw new AiModelNotEnabledException(
                    "未配置可用的 Embedding 模型，将由索引任务降级为文本检索。");
        }

        if (chunks.isEmpty()) {
            log.info("文档没有可索引分片：documentId={}", documentId);
            return new PreparedKnowledgeIndex(
                    new EmbeddingIndexResult(documentId, 0,
                            properties.embeddingModelName(), properties.embeddingDimension()),
                    List.of());
        }

        String modelName = properties.embeddingModelName();
        int configuredDimension = properties.embeddingDimension();

        List<KnowledgeChunkEmbedding> embeddings = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            float[] vector = aiEmbeddingService.embed("knowledge.document-index", chunk.content());

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

        return new PreparedKnowledgeIndex(
                new EmbeddingIndexResult(documentId, embeddings.size(), modelName, configuredDimension),
                embeddings);
    }

    /**
     * 替换一个已经完成模型计算的向量集合。
     * 调用方必须先持有有效任务租约，并在数据库事务内调用本方法。
     */
    public EmbeddingIndexResult persistPreparedIndex(PreparedKnowledgeIndex prepared) {
        EmbeddingIndexResult result = prepared.result();
        int deleted = embeddingRepository.deleteByDocumentId(result.documentId());
        if (deleted > 0) {
            log.info("已删除文档原有向量：documentId={}，数量={}", result.documentId(), deleted);
        }
        if (!prepared.embeddings().isEmpty()) {
            embeddingRepository.saveAll(prepared.embeddings());
        }
        log.info("文档向量写入完成：数量={}，documentId={}，model={}，dim={}",
                result.chunkCount(), result.documentId(), result.modelName(), result.dimension());
        return result;
    }
}
