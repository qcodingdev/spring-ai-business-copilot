package dev.qcoding.businesscopilot.guardrails;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks sensitive information embedded in free-form text.
 *
 * <p>自由文本敏感信息脱敏器。与 {@link SensitiveDataMasker} 不同，本类面向非结构化文本
 * （例如知识库文档分片）：不依赖列名，而是通过正则在文本中扫描手机号、邮箱等可识别的
 * 个人信息，以及 password/token/secret/id_card 等高危关键字后的明文进行脱敏或全遮蔽。</p>
 *
 * <p>沉淀背景：Knowledge Copilot 文档分片入库前需要脱敏，但 Data Copilot 的脱敏是基于
 * 列名的（查询结果），无法直接复用。本能力由 Knowledge Copilot 真实使用后沉淀到 ai-guardrails，
 * 不影响 Data Copilot 已有行为。</p>
 *
 * <p>策略：
 * <ul>
 *   <li>手机号（11 位，1 开头）：保留前三后四，中间替换为 ****（138****5678）。</li>
 *   <li>邮箱：保留首字符与域名，中间替换为 ***（u***@example.com）。</li>
 *   <li>身份证号（18 位，最后一位可为 X）：全遮蔽为 ********。</li>
 *   <li>password / token / secret / api_key / access_key 等高危关键字后的明文：全遮蔽。</li>
 * </ul>
 * 高危关键字遮蔽属于阻断性脱敏：这类凭据不应进入知识库内容，因此遮蔽后值不可读。</p>
 */
public class SensitiveTextMasker {

    /** 11-digit Chinese mobile number starting with 1. */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");

    /** Email address. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** 18-digit Chinese ID card number (last char may be X). */
    private static final Pattern ID_CARD_PATTERN =
            Pattern.compile("(?<![0-9Xx])[1-9]\\d{16}[0-9Xx](?![0-9Xx])");

    /** High-risk credential keywords followed by an assignment; the right-hand value is redacted. */
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)("
            + "password|passwd|pwd|token|access_token|refresh_token|api[_\\-]?key|"
            + "secret|client[_\\-]?secret|private[_\\-]?key|access[_\\-]?key"
            + ")"
            + "\\s*[:=]\\s*"
            + "(?<value>[^\\s,;\"'`)}\\]]+)");

    private static final String PHONE_MASK = "****";
    private static final String EMAIL_MASK = "***";
    private static final String FULL_MASK = "********";

    /**
     * Mask all sensitive occurrences inside {@code text}.
     *
     * @param text the raw free-form text; {@code null} and blank pass through unchanged
     * @return a new string with sensitive substrings replaced, never {@code null}
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = maskSecretAssignments(result);
        result = maskIdCards(result);
        result = maskEmails(result);
        result = maskPhones(result);
        return result;
    }

    /** Determine whether {@code text} contains any detectable sensitive value. */
    public boolean containsSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PHONE_PATTERN.matcher(text).find()
                || EMAIL_PATTERN.matcher(text).find()
                || ID_CARD_PATTERN.matcher(text).find()
                || SECRET_ASSIGNMENT_PATTERN.matcher(text).find();
    }

    private String maskPhones(String text) {
        return replaceAll(text, PHONE_PATTERN, this::maskPhone);
    }

    private String maskEmails(String text) {
        return replaceAll(text, EMAIL_PATTERN, this::maskEmail);
    }

    private String maskIdCards(String text) {
        return replaceAll(text, ID_CARD_PATTERN, m -> FULL_MASK);
    }

    private String maskSecretAssignments(String text) {
        Matcher matcher = SECRET_ASSIGNMENT_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            // 保留 "keyword=value" 的关键字部分，只把右值替换为全遮蔽，便于审计定位但不可读
            String keyword = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(keyword + "=" + FULL_MASK));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String maskPhone(Matcher matcher) {
        String digits = matcher.group().replaceAll("[^0-9]", "");
        if (digits.length() <= 7) {
            return PHONE_MASK;
        }
        return digits.substring(0, 3) + PHONE_MASK + digits.substring(digits.length() - 4);
    }

    private String maskEmail(Matcher matcher) {
        String email = matcher.group();
        int at = email.indexOf('@');
        if (at <= 0) {
            return EMAIL_MASK;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.charAt(0) + EMAIL_MASK + domain;
    }

    private String replaceAll(String text, Pattern pattern, java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        while (matcher.find()) {
            spans.add(new int[]{matcher.start(), matcher.end()});
            replacements.add(replacer.apply(matcher));
        }
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int end = spans.get(i)[1];
            sb.append(text, cursor, start);
            sb.append(replacements.get(i));
            cursor = end;
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }
}
