package dev.qcoding.businesscopilot.aicore;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AI core boundary.
 *
 * <p>AI 核心层配置：模型名称、JSON 输出容错开关等。所有模型连接相关配置仍由 Spring AI 自带属性管理。</p>
 *
 * @param modelName           human-readable model name recorded in audit logs
 * @param modelDisabled       when {@code true}, AI features report a clear error instead of failing to start
 * @param maxPromptInputChars soft cap on the user question length passed into prompts
 */
@ConfigurationProperties(prefix = "business-copilot.ai-core")
public record AiModelProperties(
        String modelName,
        boolean modelDisabled,
        int maxPromptInputChars) {

    /** Defaults applied when no configuration is present. */
    public AiModelProperties {
        if (modelName == null || modelName.isBlank()) {
            modelName = "unknown";
        }
        if (maxPromptInputChars <= 0) {
            maxPromptInputChars = 1000;
        }
    }
}
