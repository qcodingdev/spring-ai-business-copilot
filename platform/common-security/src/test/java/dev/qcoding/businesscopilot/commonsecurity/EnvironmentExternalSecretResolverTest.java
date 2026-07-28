package dev.qcoding.businesscopilot.commonsecurity;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentExternalSecretResolverTest {

    @Test
    void resolvesOnlyValidatedEnvironmentReferences() {
        var resolver = new EnvironmentExternalSecretResolver(
                new MockEnvironment().withProperty("SUPPORT_API_TOKEN", "secret-value"));

        assertThat(resolver.resolve("SUPPORT_API_TOKEN")).isEqualTo("secret-value");
        assertThatThrownBy(() -> resolver.resolve("secret-value"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("环境变量名");
    }

    @Test
    void failsClosedWhenSecretIsMissing() {
        var resolver = new EnvironmentExternalSecretResolver(new MockEnvironment());

        assertThatThrownBy(() -> resolver.resolve("MISSING_TOKEN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未在运行环境配置");
    }
}
