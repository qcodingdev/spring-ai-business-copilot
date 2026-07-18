package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportSourceImportServiceTest {

    private final ReportSourceImportService service = new ReportSourceImportService(
            new ObjectMapper(),
            new ReportSourceNormalizer(new SensitiveTextMasker(), properties()),
            properties());

    @Test
    void importsCsvWithProviderVersionAndCustomAttributes() {
        String csv = """
                sourceType,title,content,providerId,sourceVersion,observedAt,sourceTimezone,sourceUnit,validUntil,attr.region
                METRIC,Paid orders,Orders were 1284,warehouse,2026-W28,2026-07-15T00:00:00Z,Asia/Shanghai,orders,2026-07-22T00:00:00Z,cn
                """;

        var preview = service.preview("metrics.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(preview.sources()).hasSize(1);
        assertThat(preview.sources().getFirst().providerId()).isEqualTo("warehouse");
        assertThat(preview.sources().getFirst().attributes()).containsEntry("region", "cn");
    }

    @Test
    void importsJsonAndMasksSensitiveContent() {
        String json = """
                [{
                  "sourceType":"MEETING_NOTE",
                  "title":"Delivery sync",
                  "content":"Contact alex@example.com",
                  "providerId":"meeting-system",
                  "sourceVersion":"42",
                  "observedAt":"2026-07-15T00:00:00Z",
                  "sourceTimezone":"UTC"
                }]
                """;

        var preview = service.preview("notes.json", "application/json",
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(preview.sources().getFirst().sanitizedContent()).contains("a***@example.com");
    }

    @Test
    void rejectsUnsupportedOrOversizedImports() {
        assertThatThrownBy(() -> service.parse("metrics.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1}))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.parse("metrics.csv", "text/csv", new byte[1_048_577]))
                .isInstanceOf(BusinessException.class);
    }

    private static ReportCopilotProperties properties() {
        return new ReportCopilotProperties(true, 31, 10, 4000, 10, 10, 10,
                Duration.ofMinutes(30), Set.of(ReportType.TEAM_WEEKLY), true);
    }
}
