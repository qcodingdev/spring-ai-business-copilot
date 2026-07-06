package dev.qcoding.businesscopilot.aicore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateServiceTest {

    private final PromptTemplateService service = new PromptTemplateService();

    @Test
    void loadsExistingTemplate() {
        String template = service.loadTemplate("data-copilot/sql-generation.st");
        assertThat(template).contains("sql");
        assertThat(template).contains("schema whitelist");
    }

    @Test
    void rendersTemplateWithVariables() {
        String rendered = service.render("data-copilot/sql-generation.st",
                Map.of("schemaContext", "table: customers (id, name)",
                        "question", "last month sales",
                        "maxRows", "100"));
        assertThat(rendered).contains("table: customers (id, name)");
        assertThat(rendered).contains("last month sales");
        assertThat(rendered).contains("Maximum allowed limit is 100");
    }

    @Test
    void throwsWhenTemplateNotFound() {
        assertThatThrownBy(() -> service.loadTemplate("nonexistent.st"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }
}
