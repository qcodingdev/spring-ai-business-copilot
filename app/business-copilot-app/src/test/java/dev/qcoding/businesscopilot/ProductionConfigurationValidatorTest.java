package dev.qcoding.businesscopilot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationValidatorTest {

    @Test
    void rejectsDemoCredentials() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "copilot", "admin-change-me", "operator-change-me", "reviewer-change-me",
                false, "");

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo platform database password");
    }

    @Test
    void rejectsMissingBusinessQueryPasswordWhenEnabled() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "platform-secret", "admin-secret", "operator-secret", "reviewer-secret",
                true, "");

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("business query password");
    }

    @Test
    void acceptsExplicitNonDemoCredentials() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "platform-secret", "admin-secret", "operator-secret", "reviewer-secret",
                true, "business-query-secret");

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }
}
