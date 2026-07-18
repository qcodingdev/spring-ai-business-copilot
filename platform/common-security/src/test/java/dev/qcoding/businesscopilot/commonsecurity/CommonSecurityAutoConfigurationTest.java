package dev.qcoding.businesscopilot.commonsecurity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CommonSecurityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class));

    @Test
    void registersNarrowSecurityPrimitives() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CurrentActorProvider.class);
            assertThat(context).hasSingleBean(ObjectAccessPolicy.class);
            assertThat(context).hasSingleBean(ConfirmationTokenService.class);
        });
    }
}
