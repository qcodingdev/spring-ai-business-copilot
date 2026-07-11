package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReportSourceNormalizerTest {

    private final ReportSourceNormalizer normalizer = new ReportSourceNormalizer(
            new SensitiveTextMasker(),
            new ReportCopilotProperties(true, 31, 10, 200, 10, 10, 10, Duration.ofMinutes(30),
                    Set.of(ReportType.TEAM_WEEKLY), true));

    @Test
    void masksSensitiveContentAndKeepsHashStableAcrossPreviews() {
        RawReportSource raw = new RawReportSource(ReportSourceType.MEETING_NOTE, "Weekly sync",
                "Contact alex@example.com before the rollout review.", Map.of("owner", "alex@example.com"));

        List<ReportSource> first = normalizer.normalize(List.of(raw));
        List<ReportSource> second = normalizer.normalize(List.of(raw));

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().sourceId()).isNotEqualTo(second.getFirst().sourceId());
        assertThat(first.getFirst().sanitizedContent()).contains("a***@example.com");
        assertThat(first.getFirst().attributes()).containsEntry("owner", "a***@example.com");
        assertThat(first.getFirst().sourceHash()).isEqualTo(second.getFirst().sourceHash()).hasSize(64);
    }
}
