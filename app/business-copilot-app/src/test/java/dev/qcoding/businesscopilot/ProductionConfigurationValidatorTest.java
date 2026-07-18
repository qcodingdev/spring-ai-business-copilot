package dev.qcoding.businesscopilot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationValidatorTest {

    @Test
    void rejectsDemoCredentials() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "copilot", "admin-change-me", "operator-change-me", "reviewer-change-me",
                true, "business-query-secret");

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("演示平台数据库密码");
    }

    @Test
    void rejectsMissingBusinessQueryPasswordWhenEnabled() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "platform-secret", "admin-secret", "operator-secret", "reviewer-secret",
                true, "");

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("业务查询数据库密码");
    }

    @Test
    void acceptsExplicitNonDemoCredentials() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "platform-secret", "admin-secret", "operator-secret", "reviewer-secret",
                true, "business-query-secret");

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void rejectsPlatformConnectionFallbackInProduction() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "platform-secret", "admin-secret", "operator-secret", "reviewer-secret",
                false, "");

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("独立只读业务查询数据源");
    }
}
