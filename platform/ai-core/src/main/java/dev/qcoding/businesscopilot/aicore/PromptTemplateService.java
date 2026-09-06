package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Loads prompt template files from the classpath and substitutes {@code {placeholder}} variables.
 *
 * <p>从 classpath 加载 prompt 模板并替换占位符。模板集中放在 resources 中，
 * service 代码不再散落大段 prompt 文本。</p>
 */
public class PromptTemplateService {

    private static final String PROMPTS_PREFIX = "classpath:prompts/";
    private static final String ENGLISH_LOCALE = "en-US";
    private static final String ENGLISH_TEMPLATE_SUFFIX = ".en-US.st";

    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    private final PromptTemplateProvider templateProvider;

    public PromptTemplateService() {
        this(null);
    }

    public PromptTemplateService(PromptTemplateProvider templateProvider) {
        this.templateProvider = templateProvider;
    }

    /**
     * Render the template at {@code location} (e.g. {@code data-copilot/sql-generation.st}) with the
     * given variables. Template variables use the {@code {name}} syntax and are replaced verbatim.
     */
    public String render(String location, Map<String, String> variables) {
        return renderWithMetadata(location, "v1", variables).content();
    }

    /** Render a template and return stable metadata without retaining prompt content. */
    public RenderedPrompt renderWithMetadata(String location, String version,
                                             Map<String, String> variables) {
        TemplateSource source = templateSource(localizedLocation(location), version);
        String template = source.content();
        return new RenderedPrompt(
                substitute(template, variables),
                new PromptTemplateMetadata(source.location(), source.version(), source.contentHash()));
    }

    /** Load a raw template without variable substitution. */
    public String loadTemplate(String location) {
        return templateSource(localizedLocation(location), "v1").content();
    }

    private TemplateSource templateSource(String location, String fallbackVersion) {
        if (templateProvider != null) {
            Optional<PromptTemplateProvider.Template> governed = templateProvider.activeTemplate(location);
            if (governed.isPresent()) {
                PromptTemplateProvider.Template template = governed.get();
                return new TemplateSource(location, template.content(), template.version(), template.contentHash());
            }
        }
        Resource resource = resourceResolver.getResource(PROMPTS_PREFIX + location);
        if (!resource.exists()) {
            throw new IllegalStateException("未找到提示词模板：" + location);
        }
        try (var input = resource.getInputStream()) {
            String content = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            return new TemplateSource(location, content, fallbackVersion, sha256(content));
        } catch (IOException ex) {
            throw new IllegalStateException("读取提示词模板失败：" + location, ex);
        }
    }

    /**
     * 中文模板沿用稳定逻辑路径；英文模板在扩展名前增加 {@code .en-US}。
     * 这样 Prompt 治理仍可按模块扫描两个独立版本，审计也能准确区分实际语言。
     */
    private String localizedLocation(String location) {
        if (!ENGLISH_LOCALE.equals(BusinessRequestContextHolder.currentLocale())
                || location.endsWith(ENGLISH_TEMPLATE_SUFFIX)) {
            return location;
        }
        return location.endsWith(".st")
                ? location.substring(0, location.length() - 3) + ENGLISH_TEMPLATE_SUFFIX
                : location + ".en-US";
    }

    private record TemplateSource(String location, String content, String version, String contentHash) {
    }

    /** Replace every {@code {name}} occurrence with the provided value. */
    private String substitute(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            // 占位符使用 {name} 形式，按字面值替换；变量值可为 null 时替换为空串避免暴露占位符
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return result;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }
}
