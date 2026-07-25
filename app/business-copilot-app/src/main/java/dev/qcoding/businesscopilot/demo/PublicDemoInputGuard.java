package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 公网自由输入的服务端最终边界；错误消息不回显命中的敏感内容。 */
@Component
public class PublicDemoInputGuard {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\w.-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![\\w.-])");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\w)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){15,19}(?!\\d)");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?i)-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----");
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)(?:sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|api[_-]?key\\s*[:=]\\s*\\S{8,})");
    private static final Pattern PASSWORD = Pattern.compile(
            "(?i)(?:password|passwd|pwd|密码)\\s*[:=]\\s*\\S{4,}");
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)(ignore|disregard|override|forget).{0,30}(instruction|prompt|system)|"
                    + "(忽略|无视|覆盖|绕过).{0,20}(指令|规则|系统提示|安全限制)|"
                    + "(system\\s*prompt|developer\\s*message|jailbreak|越狱)");

    private final Map<DemoModule, Integer> maxLengths = new EnumMap<>(DemoModule.class);

    public PublicDemoInputGuard() {
        maxLengths.put(DemoModule.KNOWLEDGE, 500);
        maxLengths.put(DemoModule.DATA, 500);
        maxLengths.put(DemoModule.SUPPORT, 1000);
        maxLengths.put(DemoModule.HR, 2000);
        maxLengths.put(DemoModule.REPORT, 1000);
    }

    public String validateAndSanitize(DemoModule module, String input) {
        String normalized = input == null ? "" : CONTROL.matcher(input).replaceAll("").trim();
        if (normalized.isBlank()) {
            throw rejected("请输入要处理的业务内容。");
        }
        int maxLength = maxLengths.getOrDefault(module, 500);
        if (normalized.length() > maxLength) {
            throw rejected("输入内容过长，当前模块最多允许 " + maxLength + " 个字符。");
        }
        if (containsSensitiveData(normalized)) {
            throw rejected("检测到可能的个人信息、账号凭据或密钥，请删除后再试。");
        }
        if (PROMPT_INJECTION.matcher(normalized).find()) {
            throw rejected("输入包含试图绕过业务边界的指令，已拒绝执行。");
        }
        String withoutHtml = HTML_TAG.matcher(normalized).replaceAll(" ");
        return withoutHtml.replaceAll("[ \\t]{2,}", " ").trim();
    }

    public void validateSystemResource(String resourceName, String content) {
        if (resourceName == null || resourceName.isBlank() || content == null || content.isBlank()) {
            throw new IllegalStateException("虚构资源名称和内容不能为空");
        }
        if (PRIVATE_KEY.matcher(content).find() || API_KEY.matcher(content).find()
                || PASSWORD.matcher(content).find()) {
            throw new IllegalStateException("虚构资源包含疑似凭据：" + resourceName);
        }
    }

    private boolean containsSensitiveData(String value) {
        return EMAIL.matcher(value).find()
                || MOBILE.matcher(value).find()
                || ID_CARD.matcher(value).find()
                || BANK_CARD.matcher(value).find()
                || JWT.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find()
                || API_KEY.matcher(value).find()
                || PASSWORD.matcher(value).find();
    }

    private BusinessException rejected(String message) {
        return new BusinessException(ErrorCode.PUBLIC_DEMO_INPUT_REJECTED, message);
    }
}
