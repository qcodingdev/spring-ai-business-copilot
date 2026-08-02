package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
    private final AiCallCoordinator coordinator;
    private final AiOutputLocaleGuard localeGuard = new AiOutputLocaleGuard();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiChatService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                         AiModelProperties properties) {
        this(chatClientBuilderProvider, properties, standaloneCoordinator(properties));
    }

    public AiChatService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                         AiModelProperties properties,
                         AiCallCoordinator coordinator) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.properties = properties;
        this.coordinator = coordinator;
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
        return generateText("generic.text", prompt);
    }

    /** 使用固定操作名生成文本，操作名会进入链路日志和低基数指标。 */
    public String generateText(String operation, String prompt) {
        return generateTextWithMetadata(operation, prompt).content();
    }

    /**
     * Send {@code prompt} to the chat model and let Spring AI map the response to {@code type}.
     *
     * <p>{@code validateSchema()} keeps provider-agnostic prompt-based structured output while
     * retrying malformed responses. Native provider structured output is intentionally opt-in
     * because OpenAI-compatible providers do not all implement it consistently.</p>
     */
    public <T> T generateJson(String prompt, Class<T> type) {
        return generateJson("generic.json", prompt, type);
    }

    /** 使用固定操作名生成结构化结果，操作名会进入链路日志和低基数指标。 */
    public <T> T generateJson(String operation, String prompt, Class<T> type) {
        ChatClient chatClient = requireChatClient();
        try {
            T result = coordinator.execute("chat", operation, () -> chatClient.prompt()
                    .user(localizedPrompt(prompt))
                    .call()
                    .entity(type, spec -> spec.validateSchema()));
            if (localeGuard.complies(result, BusinessRequestContextHolder.currentLocale())) {
                return result;
            }
            log.warn("AI 输出语言不符合请求，执行一次安全重试：操作={}，locale={}",
                    operation, BusinessRequestContextHolder.currentLocale());
            T retried = coordinator.execute("chat", operation, () -> chatClient.prompt()
                    .user(languageRetryPrompt(prompt))
                    .call()
                    .entity(type, spec -> spec.validateSchema()));
            ensureLocale(retried);
            return retried;
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
        return generateJsonWithMetadata("generic.json", prompt, type);
    }

    public <T> AiInvocationResult<T> generateJsonWithMetadata(String operation, String prompt, Class<T> type) {
        ChatClient chatClient = requireChatClient();
        long startedAt = System.nanoTime();
        try {
            var responseEntity = coordinator.execute("chat", operation, () -> chatClient.prompt()
                    .user(localizedPrompt(prompt)).call().responseEntity(type, spec -> spec.validateSchema()));
            if (!localeGuard.complies(responseEntity.entity(),
                    BusinessRequestContextHolder.currentLocale())) {
                log.warn("AI 输出语言不符合请求，执行一次安全重试：操作={}，locale={}",
                        operation, BusinessRequestContextHolder.currentLocale());
                responseEntity = coordinator.execute("chat", operation, () -> chatClient.prompt()
                        .user(languageRetryPrompt(prompt)).call()
                        .responseEntity(type, spec -> spec.validateSchema()));
                ensureLocale(responseEntity.entity());
            }
            AiInvocationResult<T> result = new AiInvocationResult<>(
                    responseEntity.entity(),
                    metadata(responseEntity.response(), startedAt));
            coordinator.recordTokens(operation, result.metadata().inputTokens(), result.metadata().outputTokens());
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("对话模型结构化生成失败", ex);
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI 模型输出无法转换为预期结构", ex);
        }
    }

    /**
     * Provider-compatible JSON generation for models that return valid JSON inside Markdown fences
     * but do not fully support schema-assisted structured output. The prompt must describe its JSON
     * contract; parsing still rejects missing, truncated, or non-object output.
     */
    public <T> AiInvocationResult<T> generatePromptJsonWithMetadata(
            String operation, String prompt, Class<T> type) {
        AiInvocationResult<String> raw = generateTextWithMetadata(operation, prompt);
        try {
            T content = objectMapper.readValue(extractJsonObject(raw.content()), type);
            ensureLocale(content);
            return new AiInvocationResult<>(content, raw.metadata());
        } catch (JacksonException | IllegalArgumentException ex) {
            log.error("对话模型提示词 JSON 解析失败：操作={}", operation, ex);
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI 模型输出无法转换为预期结构", ex);
        }
    }

    static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AI JSON output is blank");
        }
        int start = raw.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("AI JSON object start is missing");
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return raw.substring(start, index + 1);
        }
        throw new IllegalArgumentException("AI JSON object is truncated");
    }

    /** Text generation with metadata from the exact same provider response. */
    public AiInvocationResult<String> generateTextWithMetadata(String prompt) {
        return generateTextWithMetadata("generic.text", prompt);
    }

    public AiInvocationResult<String> generateTextWithMetadata(String operation, String prompt) {
        ChatClient chatClient = requireChatClient();
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = coordinator.execute("chat", operation,
                    () -> chatClient.prompt().user(localizedPrompt(prompt)).call().chatResponse());
            String content = response != null && response.getResult() != null
                    ? response.getResult().getOutput().getText() : null;
            if (!localeGuard.complies(content, BusinessRequestContextHolder.currentLocale())) {
                log.warn("AI 输出语言不符合请求，执行一次安全重试：操作={}，locale={}",
                        operation, BusinessRequestContextHolder.currentLocale());
                response = coordinator.execute("chat", operation,
                        () -> chatClient.prompt().user(languageRetryPrompt(prompt)).call().chatResponse());
                content = response != null && response.getResult() != null
                        ? response.getResult().getOutput().getText() : null;
                ensureLocale(content);
            }
            AiInvocationResult<String> result = new AiInvocationResult<>(content, metadata(response, startedAt));
            coordinator.recordTokens(operation, result.metadata().inputTokens(), result.metadata().outputTokens());
            return result;
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

    /**
     * 所有五个业务模块共享同一显式输出语言约束。SQL、代码、字段名和引用原文保持不变；
     * locale 只使用 zh-CN/en-US，不把用户文本放入日志或指标。
     */
    private String localizedPrompt(String prompt) {
        String instruction = "en-US".equals(BusinessRequestContextHolder.currentLocale())
                ? "\n\nOUTPUT LANGUAGE REQUIREMENT: Write all user-facing natural-language fields in English. "
                + "Do not translate SQL, code, field names, quotations, citations, or uploaded source text."
                : "\n\n输出语言要求：所有面向用户的自然语言字段使用简体中文。"
                + "SQL、代码、字段名、引文、引用和用户上传原文不得翻译。";
        return prompt + instruction;
    }

    private String languageRetryPrompt(String prompt) {
        return localizedPrompt(prompt) + ("en-US".equals(BusinessRequestContextHolder.currentLocale())
                ? "\nThe previous response used the wrong language. Regenerate once in English; preserve evidence verbatim."
                : "\n上一次响应语言不符合要求。仅重新生成一次简体中文用户可见字段，并保持证据原文不变。");
    }

    private void ensureLocale(Object output) {
        if (!localeGuard.complies(output, BusinessRequestContextHolder.currentLocale())) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI 模型输出语言与当前请求不一致");
        }
    }

    private static AiCallCoordinator standaloneCoordinator(AiModelProperties properties) {
        AiResilienceProperties resilience = new AiResilienceProperties(0, null, 0, 0, 0, null);
        return new AiCallCoordinator(resilience, new AiCallMetrics(null, properties));
    }
}
