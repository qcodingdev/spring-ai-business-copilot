package dev.qcoding.businesscopilot.reportcopilot.generation;

import dev.qcoding.businesscopilot.reportcopilot.source.ReportSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportNumericGroundingTest {

    private final ReportGenerationOutputValidator validator = new ReportGenerationOutputValidator();

    private ReportSource source(String sourceId, String content) {
        return new ReportSource(sourceId, java.util.UUID.randomUUID(),
                dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType.METRIC,
                "指标来源", content, "hash", Map.of(), "fixture", "v1",
                Instant.now(), "UTC", "orders", Instant.now().plusSeconds(3600), null);
    }

    @Test
    void rejectsSummaryNumbersAbsentFromCitedSources() {
        // REP-01：摘要中的数字必须能在引用来源中找到，防止夸大或捏造。
        ReportSource source = source("s1", "本月成交额 1284 元，订单 300 单");
        var output = new LlmReportOutput(
                "本月成交额大涨至 9999 元。",
                List.of("s1"), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ReportCitation("s1", "来源")));

        var result = validator.validate(output, List.of(source));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("9999"));
    }

    @Test
    void acceptsSummaryNumbersGroundedInCitedSources() {
        ReportSource source = source("s1", "本月成交额 1284 元，订单 300 单");
        var output = new LlmReportOutput(
                "本月成交额达到 1284 元。",
                List.of("s1"), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ReportCitation("s1", "来源")));

        assertThat(validator.validate(output, List.of(source)).valid()).isTrue();
    }

    @Test
    void rejectsUnsupportedQualitativeConclusion() {
        ReportSource source = source("s1", "本月成交额 1284 元，尚未完成原因分析");
        var output = new LlmReportOutput(
                "本月业绩显著增长，主要由新客户转化导致。",
                List.of("s1"), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ReportCitation("s1", "来源")));

        assertThat(validator.validate(output, List.of(source)).violations())
                .anyMatch(item -> item.contains("陈述缺少"));
    }

    @Test
    void acceptsCompletedTaskClaimGroundedInTheCitedSourceTitle() {
        ReportSource source = new ReportSource("s1", java.util.UUID.randomUUID(),
                dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType.TASK,
                "完成 v2.0 安全验证", "状态：COMPLETED\n来源：发布检查清单", "hash",
                Map.of("status", "COMPLETED", "source", "发布检查清单"),
                "fixture", "v1", Instant.now(), "UTC", "", Instant.now().plusSeconds(3600), null);
        var output = new LlmReportOutput(
                "", List.of(), List.of(),
                List.of(new ReportItem("完成 v2.0 安全验证", List.of("s1"))),
                List.of(), List.of(), List.of(), List.of(new ReportCitation("s1", "任务标题")));

        assertThat(validator.validate(output, List.of(source)).valid()).isTrue();
    }
}
