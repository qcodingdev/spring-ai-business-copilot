package dev.qcoding.businesscopilot.reportcopilot.source;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 为 Report Copilot 演示提供可信的虚构来源数据。 */
public class SampleReportDataProvider implements ReportDataProvider {

    @Override
    public List<RawReportSource> loadSources() {
        return List.of(
                new RawReportSource(ReportSourceType.METRIC, "本周经营指标",
                        "成交额为 128400 元，支付订单 1284 单，退款率 1.8%，新增用户 356 人。",
                        Map.of("period", "2026-W27", "collectedAt", "2026-07-09T09:00:00Z"),
                        "sample-metrics", "2026-W27", Instant.parse("2026-07-09T09:00:00Z"),
                        "Asia/Shanghai", "CNY/orders/percent/users", Instant.parse("2026-07-16T09:00:00Z")),
                new RawReportSource(ReportSourceType.TASK, "支付链路监控",
                        "已完成支付失败监控和告警路由配置。负责人：产品团队。",
                        Map.of("status", "COMPLETED", "source", "weekly task board"),
                        "sample-task-board", "2026-W27", Instant.parse("2026-07-09T08:30:00Z"),
                        "Asia/Shanghai", "", Instant.parse("2026-07-16T08:30:00Z")),
                new RawReportSource(ReportSourceType.TASK, "移动端发布验证",
                        "当前阻塞：移动端发布验证正在等待外部沙箱恢复。负责人：交付团队。",
                        Map.of("status", "BLOCKED", "source", "weekly task board"),
                        "sample-task-board", "2026-W27", Instant.parse("2026-07-09T08:30:00Z"),
                        "Asia/Shanghai", "", Instant.parse("2026-07-16T08:30:00Z")),
                new RawReportSource(ReportSourceType.MEETING_NOTE, "周交付例会",
                        "决定：支付失败监控连续稳定七天前，灰度比例保持 20%。行动：下周二复盘灰度指标。",
                        Map.of("recordedAt", "2026-07-08T10:00:00Z", "source", "weekly delivery sync"),
                        "sample-meeting-notes", "2026-W27", Instant.parse("2026-07-08T10:00:00Z"),
                        "Asia/Shanghai", "", Instant.parse("2026-07-15T10:00:00Z")));
    }
}
