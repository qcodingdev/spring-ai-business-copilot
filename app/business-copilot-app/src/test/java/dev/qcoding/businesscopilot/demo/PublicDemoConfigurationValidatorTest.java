package dev.qcoding.businesscopilot.demo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicDemoConfigurationValidatorTest {

    @Test
    void rejectsDefaultFingerprintSecret() {
        PublicDemoConfigurationValidator validator = new PublicDemoConfigurationValidator(
                new RuntimeModeProperties("public-demo"),
                "admin-password-long",
                "operator-password-long",
                "reviewer-password-long",
                "development-only-change-me");

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("默认客户端摘要密钥");
    }

    @Test
    void acceptsExplicitLongSecretsInPublicDemoMode() {
        PublicDemoConfigurationValidator validator = new PublicDemoConfigurationValidator(
                new RuntimeModeProperties("public-demo"),
                "admin-password-long",
                "operator-password-long",
                "reviewer-password-long",
                "fingerprint-secret-long");

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void rejectsProfileAndRuntimeModeMismatch() {
        PublicDemoConfigurationValidator validator = new PublicDemoConfigurationValidator(
                new RuntimeModeProperties("self-hosted"),
                "admin-password-long",
                "operator-password-long",
                "reviewer-password-long",
                "fingerprint-secret-long");

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .hasMessageContaining("public-demo 运行模式");
    }
}
