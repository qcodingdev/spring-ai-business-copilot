package dev.qcoding.businesscopilot.resumecopilot.privacy;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.resumecopilot.ResumeCopilotProperties;

import java.util.Locale;
import java.util.regex.Pattern;

/** 在调用 AI 或入库前移除可执行标记、提示词注入、联系方式、身份信息和受保护属性。 */
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
            "(?i)(年龄|性别|男性|女性|限男|限女|未婚|已婚|婚育|婚姻|民族|宗教|健康|残障|政治面貌|籍贯|照片|"
                    + "毕业年份|应届生|年轻团队|形象气质|稳定性|本地户口|家庭稳定|长期加班|996|"
                    + "age requirement|under\\s+\\d{1,2}|younger than|gender|male|female|marital|ethnicity|religion|disability|"
                    + "graduation year|recent graduate|young team|appearance|local household|family status|"
                    + "culture fit|work overtime)");
    private static final Pattern RESIDUAL_PROTECTED_ATTRIBUTE = Pattern.compile(
            "(?i)(?:^|[，,。;；\\s])(男|女|已婚|未婚|汉族|\\d{1,3}\\s*岁|\\d{4}\\s*年出生)(?:$|[，,。;；\\s])");

    private final ResumeCopilotProperties properties;

    public ResumePrivacySanitizer(ResumeCopilotProperties properties) {
        this.properties = properties;
    }

    public String sanitizeJobDescription(String value) {
        validate(value, properties.maxJobDescriptionLength(), "职位描述");
        if (properties.protectedAttributeGuardEnabled() && PROTECTED_CRITERION.matcher(value).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "职位描述包含受保护属性或代理筛选条件，无法继续分析。");
        }
        return clean(value, false);
    }

    public String sanitizeResume(String value) {
        validate(value, properties.maxResumeLength(), "简历");
        String sanitized = clean(value, true);
        if (properties.protectedAttributeGuardEnabled() && RESIDUAL_PROTECTED_ATTRIBUTE.matcher(sanitized).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "简历中仍包含受保护属性，请先人工脱敏后再提交。");
        }
        if (sanitized.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "简历中没有可用的工作相关内容。");
        }
        return sanitized;
    }

    public String sanitizeReviewerFeedback(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        validate(value, properties.maxReviewerFeedbackLength(), "复核意见");
        if (properties.protectedAttributeGuardEnabled() && PROTECTED_CRITERION.matcher(value).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "复核意见包含受保护属性或代理招聘条件。");
        }
        return clean(value, true);
    }

    private String clean(String value, boolean removeProtectedLines) {
        String text = SCRIPT.matcher(value).replaceAll(" ");
        text = HTML.matcher(text).replaceAll(" ");
        text = EMAIL.matcher(text).replaceAll("[邮箱已移除]");
        text = PHONE.matcher(text).replaceAll("[手机号已移除]");
        text = ID_CARD.matcher(text).replaceAll("[证件号已移除]");
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
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + "不能为空。");
        }
        if (value.length() > limit) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + "超过配置长度限制。");
        }
        if (value.toLowerCase(Locale.ROOT).contains("data:text/html")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, label + "包含不支持的可执行内容。");
        }
    }
}
