package dev.qcoding.businesscopilot.aicore;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Low-cardinality provider and model identity used for embedding metrics and usage audit. */
@ConfigurationProperties(prefix = "business-copilot.ai-core.embedding")
public record AiEmbeddingProperties(String modelName, String providerName) {

    @ConstructorBinding
    public AiEmbeddingProperties {
        if (modelName == null || modelName.isBlank()) {
            modelName = "unknown";
        }
        if (providerName == null || providerName.isBlank()) {
            providerName = "unknown";
        }
    }
}
