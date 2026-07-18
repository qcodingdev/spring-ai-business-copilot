package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
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

    public String providerName() {
        return properties.providerName();
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
            log.error("对话模型文本生成失败", ex);
            // 模型调用异常转为业务可理解错误，不暴露底层 SDK 细节
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    "AI 对话模型调用失败", ex);
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
            log.error("对话模型结构化生成失败", ex);
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI 模型输出无法转换为预期结构", ex);
        }
    }

    /** Structured generation with metadata from the exact same provider response. */
    public <T> AiInvocationResult<T> generateJsonWithMetadata(String prompt, Class<T> type) {
        ChatClient chatClient = requireChatClient();
        long startedAt = System.nanoTime();
        try {
            var responseEntity = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .responseEntity(type, spec -> spec.validateSchema());
            return new AiInvocationResult<>(
                    responseEntity.entity(),
                    metadata(responseEntity.response(), startedAt));
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("对话模型结构化生成失败", ex);
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI 模型输出无法转换为预期结构", ex);
        }
    }

    /** Text generation with metadata from the exact same provider response. */
    public AiInvocationResult<String> generateTextWithMetadata(String prompt) {
        ChatClient chatClient = requireChatClient();
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
            String content = response != null && response.getResult() != null
                    ? response.getResult().getOutput().getText() : null;
            return new AiInvocationResult<>(content, metadata(response, startedAt));
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("对话模型文本生成失败", ex);
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR,
                    "AI 对话模型调用失败", ex);
        }
    }

    private AiInvocationMetadata metadata(ChatResponse response, long startedAt) {
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        if (response == null) {
            return new AiInvocationMetadata(
                    properties.providerName(), properties.modelName(), null,
                    null, null, null, latencyMs);
        }
        var responseMetadata = response.getMetadata();
        var usage = responseMetadata != null ? responseMetadata.getUsage() : null;
        var generation = response.getResult();
        String finishReason = generation != null && generation.getMetadata() != null
                ? generation.getMetadata().getFinishReason() : null;
        return new AiInvocationMetadata(
                properties.providerName(),
                responseMetadata != null && responseMetadata.getModel() != null
                        && !responseMetadata.getModel().isBlank()
                        ? responseMetadata.getModel() : properties.modelName(),
                responseMetadata != null && responseMetadata.getId() != null
                        && !responseMetadata.getId().isBlank()
                        ? responseMetadata.getId() : null,
                usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null,
                finishReason,
                latencyMs);
    }

    private ChatClient requireChatClient() {
        if (properties.modelDisabled()) {
            throw new AiModelNotEnabledException(
                    "AI 对话模型已被配置项 business-copilot.ai-core.model-disabled=true 禁用。");
        }
        ChatClient.Builder builder;
        try {
            builder = chatClientBuilderProvider.getIfAvailable();
        } catch (BeansException ex) {
            throw new AiModelNotEnabledException(
                    "未配置 AI 对话模型，请设置 spring.ai.model.chat 并提供模型凭证。");
        }
        if (builder == null) {
            // chat model 为 none 时 ChatClient.Builder bean 不会被创建，给出清晰错误
            throw new AiModelNotEnabledException(
                    "未配置 AI 对话模型，请设置 spring.ai.model.chat 并提供模型凭证。");
        }
        return builder.build();
    }
}
