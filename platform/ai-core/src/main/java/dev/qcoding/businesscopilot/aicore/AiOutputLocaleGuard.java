package dev.qcoding.businesscopilot.aicore;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对模型用户可见自然语言字段执行低风险语言校验。
 *
 * <p>SQL、代码、引用、原文和未知结构不参与判断，避免为了界面语言篡改业务证据。</p>
 */
final class AiOutputLocaleGuard {

    private static final Pattern USER_VISIBLE_FIELD = Pattern.compile(
            "(?i).*(answer|reply|draft|summary|reason|recommend|description|message|"
                    + "question|title|body|text|section|gap|requirement|qualification).*");

    boolean complies(Object output, String locale) {
        List<String> values = new ArrayList<>();
        collect(output, output instanceof CharSequence, values);
        if (values.isEmpty()) return true;

        int cjk = 0;
        int latin = 0;
        for (String value : values) {
            for (int offset = 0; offset < value.length();) {
                int point = value.codePointAt(offset);
                offset += Character.charCount(point);
                if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) cjk++;
                if ((point >= 'A' && point <= 'Z') || (point >= 'a' && point <= 'z')) latin++;
            }
        }
        if ("en-US".equals(locale)) return cjk == 0;
        return cjk > 0 || latin < 8;
    }

    private void collect(Object value, boolean inspectAll, List<String> values) {
        if (value == null) return;
        if (value instanceof CharSequence text) {
            if (inspectAll && !text.isEmpty()) values.add(text.toString());
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) collect(item, inspectAll, values);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> collect(item,
                    inspectAll || USER_VISIBLE_FIELD.matcher(String.valueOf(key)).matches(), values));
            return;
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                collect(Array.get(value, index), inspectAll, values);
            }
            return;
        }
        if (!value.getClass().isRecord()) return;
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                boolean visible = inspectAll || USER_VISIBLE_FIELD.matcher(component.getName()).matches();
                collect(component.getAccessor().invoke(value), visible, values);
            } catch (ReflectiveOperationException ignored) {
                // 无法安全读取的未知字段不参与语言判断，业务 Guardrail 仍会继续执行。
            }
        }
    }
}
