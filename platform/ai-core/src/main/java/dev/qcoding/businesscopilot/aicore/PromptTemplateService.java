package dev.qcoding.businesscopilot.aicore;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads prompt template files from the classpath and substitutes {@code {placeholder}} variables.
 *
 * <p>从 classpath 加载 prompt 模板并替换占位符。模板集中放在 resources 中，
 * service 代码不再散落大段 prompt 文本。</p>
 */
public class PromptTemplateService {

    private static final String PROMPTS_PREFIX = "classpath:prompts/";

    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /**
     * Render the template at {@code location} (e.g. {@code data-copilot/sql-generation.st}) with the
     * given variables. Template variables use the {@code {name}} syntax and are replaced verbatim.
     */
    public String render(String location, Map<String, String> variables) {
        String template = loadTemplate(location);
        return substitute(template, variables);
    }

    /** Load a raw template without variable substitution. */
    public String loadTemplate(String location) {
        Resource resource = resourceResolver.getResource(PROMPTS_PREFIX + location);
        if (!resource.exists()) {
            throw new IllegalStateException("Prompt template not found: " + location);
        }
        try (var input = resource.getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read prompt template: " + location, ex);
        }
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
}
