package dev.qcoding.businesscopilot.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 产品运行模式配置。 */
@ConfigurationProperties(prefix = "business-copilot")
public record RuntimeModeProperties(String runtimeMode) {
    public RuntimeMode mode() {
        return RuntimeMode.from(runtimeMode);
    }
}
