package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Thin wrapper around Spring AI {@link ChatClient} that provides text and structured generation,
 * translating model failures into business-understandable exceptions.
 *
 * <p>封装 Spring AI ChatClient，对外提供 {@link #generateText(String)} 与
 * {@link #generateJson(String, Class)} 两类方法。当 chat model 未启用（例如
 * {@code spring.ai.model.chat=none}）时调用会抛出清晰错误，而不是空指针。</p>
 */
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final AiModelProperties properties;

    public AiChatService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                         AiModelProperties properties) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.properties = properties;
    }

    /** Whether a usable chat model is configured. */
    public boolean isModelEnabled() {
        if (properties.modelDisabled()) {
            return false;
        }
        try {
            return chatClientBuilderProvider.getIfAvailable() != null;
        } catch (BeansException ex) {
            return false;
        }
    }

    /** Configured model name, recorded in audit logs. */
    public String modelName() {
        return properties.modelName();
    }

    /**
     * Send {@code prompt} to the chat model and return the raw text response.
     */
    public String generateText(String prompt) {
        ChatClient chatClient = requireChatClient();
        try {
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Chat model text generation failed", ex);
            // 模型调用异常转为业务可理解错误，不暴露底层 SDK 细节
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    "AI model invocation failed", ex);
        }
    }

    /**
     * Send {@code prompt} to the chat model and let Spring AI map the response to {@code type}.
     *
     * <p>{@code validateSchema()} keeps provider-agnostic prompt-based structured output while
     * retrying malformed responses. Native provider structured output is intentionally opt-in
     * because OpenAI-compatible providers do not all implement it consistently.</p>
     */
    public <T> T generateJson(String prompt, Class<T> type) {
        ChatClient chatClient = requireChatClient();
        try {
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(type, spec -> spec.validateSchema());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Chat model structured generation failed", ex);
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI model output could not be mapped to the expected schema", ex);
        }
    }

    private ChatClient requireChatClient() {
        if (properties.modelDisabled()) {
            throw new AiModelNotEnabledException(
                    "AI chat model is disabled (business-copilot.ai-core.model-disabled=true).");
        }
        ChatClient.Builder builder;
        try {
            builder = chatClientBuilderProvider.getIfAvailable();
        } catch (BeansException ex) {
            throw new AiModelNotEnabledException(
                    "No AI chat model is configured. Set spring.ai.model.chat and provide API credentials.");
        }
        if (builder == null) {
            // chat model 为 none 时 ChatClient.Builder bean 不会被创建，给出清晰错误
            throw new AiModelNotEnabledException(
                    "No AI chat model is configured. Set spring.ai.model.chat and provide API credentials.");
        }
        return builder.build();
    }
}
