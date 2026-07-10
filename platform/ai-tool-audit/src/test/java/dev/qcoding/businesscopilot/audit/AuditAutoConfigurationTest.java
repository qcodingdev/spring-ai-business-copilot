package dev.qcoding.businesscopilot.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuditAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

    @Test
    void doesNotRegisterAuditBeansWithoutJdbcTemplate() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AuditService.class);
            assertThat(context).doesNotHaveBean(QueryAuditRepository.class);
        });
    }

    @Test
    void registersAuditBeansWhenJdbcTemplateIsAvailable() {
        contextRunner.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditService.class);
                    assertThat(context).hasSingleBean(QueryAuditRepository.class);
                });
    }
}
