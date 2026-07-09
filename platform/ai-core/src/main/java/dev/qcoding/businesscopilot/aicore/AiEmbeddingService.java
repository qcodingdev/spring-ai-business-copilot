package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around Spring AI {@link EmbeddingModel} that provides text embedding,
 * translating model failures into business-understandable exceptions.
 *
 * <p>封装 Spring AI EmbeddingModel，对外提供 {@link #embed(String)} 方法。
 * 当 embedding model 未启用（例如 {@code spring.ai.model.embedding=none}）
 * 时调用会抛出清晰错误，而不是空指针。</p>
 *
 * <p>设计上与 {@link AiChatService} 完全对称，使用 {@link ObjectProvider} 优雅处理
 * embedding model 不可用的场景。</p>
 */
@Service
public class AiEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(AiEmbeddingService.class);

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final AiModelProperties properties;

    public AiEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                               AiModelProperties properties) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.properties = properties;
    }

    /** Whether a usable embedding model is configured. */
    public boolean isModelEnabled() {
        if (properties.modelDisabled()) {
            return false;
        }
        try {
            return embeddingModelProvider.getIfAvailable() != null;
        } catch (BeansException ex) {
            return false;
        }
    }

    /** Configured chat model name, recorded in audit logs. */
    public String modelName() {
        return properties.modelName();
    }

    /**
     * Generate an embedding vector for the given text.
     *
     * @param text the input text to embed
     * @return float array of embedding values
     * @throws AiModelNotEnabledException if no embedding model is configured
     */
    public float[] embed(String text) {
        EmbeddingModel model = requireEmbeddingModel();
        try {
            return model.embed(text);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Embedding model invocation failed", ex);
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    "AI embedding model invocation failed", ex);
        }
    }

    /**
     * Return the dimension the embedding model reports.
     *
     * @return embedding dimension, or 0 if model is not available
     */
    public int dimensions() {
        if (!isModelEnabled()) {
            return 0;
        }
        return requireEmbeddingModel().dimensions();
    }

    private EmbeddingModel requireEmbeddingModel() {
        if (properties.modelDisabled()) {
            throw new AiModelNotEnabledException(
                    "AI model is disabled (business-copilot.ai-core.model-disabled=true).");
        }
        EmbeddingModel model;
        try {
            model = embeddingModelProvider.getIfAvailable();
        } catch (BeansException ex) {
            throw new AiModelNotEnabledException(
                    "No AI embedding model is configured. Set spring.ai.model.embedding and provide API credentials.");
        }
        if (model == null) {
            throw new AiModelNotEnabledException(
                    "No AI embedding model is configured. Set spring.ai.model.embedding and provide API credentials.");
        }
        return model;
    }
}
