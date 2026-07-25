package dev.qcoding.businesscopilot.demo;

import java.util.Locale;

/** 同一代码库支持的稳定运行模式。 */
public enum RuntimeMode {
    DEVELOPMENT,
    SELF_HOSTED,
    PUBLIC_DEMO;

    public static RuntimeMode from(String value) {
        if (value == null || value.isBlank()) {
            return DEVELOPMENT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "public-demo", "public_demo" -> PUBLIC_DEMO;
            case "self-hosted", "self_hosted" -> SELF_HOSTED;
            default -> DEVELOPMENT;
        };
    }

    public String propertyValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
