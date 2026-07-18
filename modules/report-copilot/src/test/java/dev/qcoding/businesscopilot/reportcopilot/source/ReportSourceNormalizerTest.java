package dev.qcoding.businesscopilot.reportcopilot.source;

import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.reportcopilot.ReportCopilotProperties;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    @Test
    void calculatesFreshnessFromImmutableObservationMetadata() {
        ReportSourceNormalizer fixedNormalizer = new ReportSourceNormalizer(
                new SensitiveTextMasker(),
                new ReportCopilotProperties(true, 31, 10, 200, 10, 10, 10,
                        Duration.ofMinutes(30), Set.of(ReportType.TEAM_WEEKLY), true),
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
        RawReportSource raw = new RawReportSource(
                ReportSourceType.METRIC, "Orders", "Orders: 1284", Map.of(),
                "warehouse", "2026-W28", Instant.parse("2026-07-15T00:00:00Z"),
                "Asia/Shanghai", "orders", Instant.parse("2026-07-17T00:00:00Z"));

        ReportSource source = fixedNormalizer.normalize(List.of(raw)).getFirst();

        assertThat(source.freshness()).isEqualTo(SourceFreshness.FRESH);
        assertThat(source.providerId()).isEqualTo("warehouse");
        assertThat(source.snapshotId().toString()).isEqualTo(source.sourceId());
    }
}
