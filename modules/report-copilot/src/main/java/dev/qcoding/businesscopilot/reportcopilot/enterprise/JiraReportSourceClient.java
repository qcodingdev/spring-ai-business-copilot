package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.source.RawReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded Jira enhanced search. Dates describe issues' latest update, not full change history. */
public class JiraReportSourceClient {
    private final ExternalHttpClientFactory clients;

    public JiraReportSourceClient(ExternalHttpClientFactory clients) {
        this.clients = clients;
    }

    public List<RawReportSource> collect(ReportEnterpriseService.Connection connection, ReportPeriod period, String authorization) {
        List<String> projects = projectKeys(connection.jiraProjectKeys());
        if (projects.isEmpty()) throw incomplete("请先配置 Jira 项目范围");
        Instant start = period.periodStart().atStartOfDay(ZoneId.of(period.timezone())).toInstant();
        Instant end = period.periodEnd().plusDays(1).atStartOfDay(ZoneId.of(period.timezone())).toInstant();
        // JQL dates use the Jira account timezone. Broaden by two days, then enforce exact instants locally.
        // This avoids silently missing boundary issues when Jira and the reporting user have different zones.
        String lower = start.atZone(ZoneOffset.UTC).toLocalDate().minusDays(2).toString();
        String upper = end.atZone(ZoneOffset.UTC).toLocalDate().plusDays(2).toString();
        String jql = "project in (" + projects.stream().map(key -> "\"" + key + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + ") AND updated >= \"" + lower
                + "\" AND updated < \"" + upper + "\" ORDER BY updated ASC, key ASC";
        var client = clients.builder(connection.baseUrl()).defaultHeader("Authorization", authorization).build();
        var settings = clients.properties();
        Map<String, RawReportSource> sources = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        Set<String> seenTokens = new HashSet<>();
        String next = null;
        int fetched = 0;
        long started = System.nanoTime();
        Instant collectedAt = Instant.now();
        for (int page = 0; page < settings.maxPages(); page++) {
            clients.ensureWithinTaskTimeout(started);
            var uri = UriComponentsBuilder.fromUriString(connection.baseUrl().replaceFirst("/+$", "")).path("/rest/api/3/search/jql")
                    .queryParam("jql", "{jql}").queryParam("fields", "summary,status,updated,project")
                    .queryParam("maxResults", Math.min(100, settings.maxItems()));
            if (next != null) uri.queryParam("nextPageToken", "{cursor}");
            JsonNode response = clients.validatePayload(client.get().uri(uri.encode().buildAndExpand(Map.of("jql", jql, "cursor", next == null ? "" : next)).toUri())
                    .retrieve().body(JsonNode.class));
            clients.ensureWithinTaskTimeout(started);
            JsonNode issues = response.path("issues");
            if (!issues.isArray()) throw incomplete("Jira 没有返回有效条目列表");
            fetched += issues.size();
            if (fetched > settings.maxItems()) throw incomplete("Jira 来源超过条目上限，请缩小项目或周期范围");
            for (JsonNode issue : issues) {
                JsonNode fields = issue.path("fields");
                String key = issue.path("key").asText("");
                String project = fields.path("project").path("key").asText("");
                if (key.isBlank() || !projects.contains(project)) throw incomplete("Jira 来源不在配置的项目范围内");
                String updated = fields.path("updated").asText("");
                Instant eventTime = eventTime(updated);
                String previous = versions.putIfAbsent(key, updated);
                if (previous != null && !previous.equals(updated)) throw incomplete("Jira 条目在采集期间变化，请重新采集");
                if (eventTime.isBefore(start) || !eventTime.isBefore(end)) continue;
                String summary = fields.path("summary").asText("");
                String status = fields.path("status").path("name").asText("");
                sources.putIfAbsent(key, new RawReportSource(ReportSourceType.TASK, key + " " + summary,
                        "状态：" + status + "；" + summary + "；最后更新时间：" + eventTime,
                        Map.of("issueKey", key, "projectKey", project, "periodStart", start.toString(),
                                "periodEndExclusive", end.toString(), "collectedAt", collectedAt.toString(),
                                "definition", "周期内最后更新的工单快照，不代表全部历史变更",
                                "completeness", "ALL_SEARCH_PAGES"),
                        connection.connectionKey(), updated, eventTime, period.timezone(), "",
                        collectedAt.plus(Duration.ofDays(1))));
            }
            if (response.path("isLast").asBoolean(false)) return List.copyOf(sources.values());
            next = response.path("nextPageToken").asText("");
            if (next.isBlank() || next.length() > 4096 || !seenTokens.add(next)) {
                throw incomplete("Jira 分页未完整结束或游标重复");
            }
        }
        throw incomplete("Jira 来源超过分页上限，请缩小项目或周期范围");
    }

    static List<String> projectKeys(List<String> keys) {
        if (keys == null) return List.of();
        if (keys.size() > 20 || keys.stream().anyMatch(key -> key == null || !key.matches("[A-Z][A-Z0-9_]{0,49}"))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Jira 项目键无效，最多配置 20 个项目");
        }
        return keys.stream().distinct().sorted().toList();
    }

    private Instant eventTime(String value) {
        try {
            try { return OffsetDateTime.parse(value).toInstant(); }
            catch (java.time.DateTimeException ex) {
                return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant();
            }
        } catch (java.time.DateTimeException ex) {
            throw incomplete("Jira 条目缺少可验证的更新时间");
        }
    }

    private BusinessException incomplete(String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, reason + "；来源不完整，报告未生成");
    }
}
