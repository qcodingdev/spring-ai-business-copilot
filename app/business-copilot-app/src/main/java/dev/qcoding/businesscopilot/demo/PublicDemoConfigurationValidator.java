package dev.qcoding.businesscopilot.demo;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 公网体验启动前拒绝默认凭据和可预测的客户端摘要密钥。 */
@Component
@Profile("public-demo")
class PublicDemoConfigurationValidator implements SmartInitializingSingleton {

    private static final Map<String, String> FORBIDDEN = Map.of(
            "管理员密码", "admin-change-me",
            "操作员密码", "operator-change-me",
            "审计员密码", "reviewer-change-me",
            "客户端摘要密钥", "development-only-change-me");
    private final Map<String, String> configured;
    private final RuntimeModeProperties runtimeModeProperties;

    PublicDemoConfigurationValidator(
            RuntimeModeProperties runtimeModeProperties,
            @Value("${business-copilot.security.admin.password:}") String adminPassword,
            @Value("${business-copilot.security.operator.password:}") String operatorPassword,
            @Value("${business-copilot.security.reviewer.password:}") String reviewerPassword,
            @Value("${business-copilot.public-demo.fingerprint-secret:}") String fingerprintSecret) {
        this.runtimeModeProperties = runtimeModeProperties;
        this.configured = Map.of(
                "管理员密码", value(adminPassword),
                "操作员密码", value(operatorPassword),
                "审计员密码", value(reviewerPassword),
                "客户端摘要密钥", value(fingerprintSecret));
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (runtimeModeProperties.mode() != RuntimeMode.PUBLIC_DEMO) {
            throw new IllegalStateException("public-demo profile 必须使用 public-demo 运行模式");
        }
        configured.forEach((name, value) -> {
            if (value.isBlank()) {
                throw new IllegalStateException("公网体验必须显式配置" + name);
            }
            if (value.equals(FORBIDDEN.get(name))) {
                throw new IllegalStateException("公网体验禁止使用默认" + name);
            }
            if (value.length() < 16) {
                throw new IllegalStateException("公网体验的" + name + "长度不能少于 16 个字符");
            }
        });
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
