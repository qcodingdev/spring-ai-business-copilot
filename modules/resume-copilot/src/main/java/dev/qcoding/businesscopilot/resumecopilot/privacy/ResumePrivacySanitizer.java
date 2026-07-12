package dev.qcoding.businesscopilot.resumecopilot.privacy;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;

import java.util.Locale;
import java.util.regex.Pattern;

/** Removes executable markup, prompt injection, contact data, identity data, and protected attributes before AI or storage. */
public class ResumePrivacySanitizer {
    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?i)(?<!\\w)\\d{17}[0-9x](?!\\w)");
    private static final Pattern PROTECTED_LINE = Pattern.compile(
            "(?i)^\\s*(姓名|name|性别|gender|年龄|age|出生(?:日期)?|birth(?:day| date)?|婚姻|婚育|marital|民族|ethnicity|宗教|religion|健康|health|残障|disability|政治面貌|籍贯|hometown|家庭|family|照片|photo|住址|地址|address|微信|wechat|qq)\\s*[:：].*$");
    private static final Pattern INJECTION_LINE = Pattern.compile(
            "(?i)^.*(ignore (all |the )?previous|system prompt|developer message|follow these instructions|忽略.{0,8}(指令|要求)|系统提示词|执行以下命令).*$");
    private static final Pattern PROTECTED_CRITERION = Pattern.compile(
            "(?i)(年龄|性别|婚育|婚姻|民族|宗教|健康|残障|政治面貌|籍贯|照片|age requirement|gender|marital|ethnicity|religion|disability)");
    private static final Pattern RESIDUAL_PROTECTED_ATTRIBUTE = Pattern.compile(
            "(?i)(?:^|[，,。;；\\s])(男|女|已婚|未婚|汉族|\\d{1,3}\\s*岁|\\d{4}\\s*年出生)(?:$|[，,。;；\\s])");

    private final ResumeCopilotProperties properties;

    public ResumePrivacySanitizer(ResumeCopilotProperties properties) {
        this.properties = properties;
    }

    public String sanitizeJobDescription(String value) {
        validate(value, properties.maxJobDescriptionLength(), "Job description");
        if (properties.protectedAttributeGuardEnabled() && PROTECTED_CRITERION.matcher(value).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The job description contains a protected-attribute criterion and cannot be analyzed.");
        }
        return clean(value, false);
    }

    public String sanitizeResume(String value) {
        validate(value, properties.maxResumeLength(), "Resume");
        String sanitized = clean(value, true);
        if (properties.protectedAttributeGuardEnabled() && RESIDUAL_PROTECTED_ATTRIBUTE.matcher(sanitized).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The resume still contains a protected attribute and requires manual sanitization.");
        }
        if (sanitized.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The resume contains no usable work-related content.");
        }
        return sanitized;
    }

    private String clean(String value, boolean removeProtectedLines) {
        String text = SCRIPT.matcher(value).replaceAll(" ");
        text = HTML.matcher(text).replaceAll(" ");
        text = EMAIL.matcher(text).replaceAll("[EMAIL_REMOVED]");
        text = PHONE.matcher(text).replaceAll("[PHONE_REMOVED]");
        text = ID_CARD.matcher(text).replaceAll("[ID_REMOVED]");
        StringBuilder result = new StringBuilder();
        for (String line : text.replace("\r", "").split("\n")) {
            if (INJECTION_LINE.matcher(line).matches()) continue;
            if (removeProtectedLines && PROTECTED_LINE.matcher(line).matches()) continue;
            String normalized = line.trim();
            if (!normalized.isEmpty()) result.append(normalized).append('\n');
        }
        return result.toString().trim();
    }

    private void validate(String value, int limit, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + " is required.");
        }
        if (value.length() > limit) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + " exceeds the configured length limit.");
        }
        if (value.toLowerCase(Locale.ROOT).contains("data:text/html")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + " contains unsupported executable content.");
        }
    }
}
