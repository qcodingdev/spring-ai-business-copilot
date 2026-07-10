package dev.qcoding.businesscopilot.supportcopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.FallbackSupportKnowledgeRetriever;
import dev.qcoding.businesscopilot.supportcopilot.knowledge.SupportKnowledgeRetriever;
import dev.qcoding.businesscopilot.supportcopilot.ticket.TicketAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SupportCopilotAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SupportCopilotAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(AiChatService.class, () -> mock(AiChatService.class))
            .withBean(PromptTemplateService.class, PromptTemplateService::new)
            .withBean(SensitiveTextMasker.class, SensitiveTextMasker::new);

    @Test
    void isNotRegisteredWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(TicketAnalysisService.class);
            assertThat(context).doesNotHaveBean(SupportKnowledgeRetriever.class);
        });
    }

    @Test
    void isRegisteredWhenEnabled() {
        contextRunner.withPropertyValues("business-copilot.support-copilot.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(TicketAnalysisService.class);
                    assertThat(context).hasSingleBean(SupportKnowledgeRetriever.class);
                    assertThat(context.getBean(SupportKnowledgeRetriever.class))
                            .isInstanceOf(FallbackSupportKnowledgeRetriever.class);
                });
    }
}
