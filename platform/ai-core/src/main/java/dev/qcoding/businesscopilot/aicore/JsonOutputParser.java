package dev.qcoding.businesscopilot.aicore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses model text output into structured JSON objects.
 *
 * <p>解析模型输出文本为 JSON 对象。模型经常把 JSON 包裹在 markdown 代码块或多余文字中，
 * 这里先做容错提取再反序列化；解析失败抛出业务可理解的异常。</p>
 */
@Component
public class JsonOutputParser {

    /** Matches the first JSON object inside an optional ```json fenced block. */
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private final ObjectMapper objectMapper;

    public JsonOutputParser() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public JsonOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse the raw model output into the given type, tolerating fenced code blocks and surrounding prose.
     */
    public <T> T parse(String rawOutput, Class<T> type) {
        if (rawOutput == null || rawOutput.isBlank()) {
            // 模型返回空内容时给出明确错误，避免下游 NPE
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI model returned empty output");
        }
        String json = extractJsonObject(rawOutput);
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_PARSE_ERROR,
                    "AI model output could not be parsed as expected JSON", ex);
        }
    }

    /** Extract the first JSON object substring from arbitrary model output. */
    private String extractJsonObject(String rawOutput) {
        // 优先剥离 ```json ... ``` 围栏
        String trimmed = rawOutput.trim();
        if (trimmed.contains("```")) {
            int fenceStart = trimmed.indexOf("```");
            int contentStart = trimmed.indexOf('\n', fenceStart);
            int fenceEnd = trimmed.indexOf("```", fenceStart + 3);
            if (contentStart > 0 && fenceEnd > contentStart) {
                return trimmed.substring(contentStart + 1, fenceEnd).trim();
            }
        }
        Matcher matcher = JSON_OBJECT.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return trimmed;
    }
}
