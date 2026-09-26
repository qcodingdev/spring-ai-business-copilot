package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportComparisonCalculator;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportDraftResponse;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.reportcopilot.source.RawReportSource;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportSourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Report 多来源聚合、环比异常和定时待确认草稿。 */
public class ReportEnterpriseService {

    private final JdbcTemplate jdbcTemplate;
    private final ReportGenerationService generationService;
    private final CurrentActorProvider actorProvider;
    private final ExternalSecretResolver secretResolver;
    private final ObjectMapper objectMapper;
    private final ExternalEndpointPolicy endpointPolicy;
    private final ExternalHttpClientFactory clientFactory;
    private final java.util.concurrent.Executor worker;
    private final ReportLifecycleService lifecycle;

    public ReportEnterpriseService(
            JdbcTemplate jdbcTemplate,
            ReportGenerationService generationService,
            CurrentActorProvider actorProvider,
            ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy,
            ExternalHttpClientFactory clientFactory) {
        this(jdbcTemplate, generationService, actorProvider, secretResolver, objectMapper,
                endpointPolicy, clientFactory, Runnable::run);
    }

    public ReportEnterpriseService(
            JdbcTemplate jdbcTemplate, ReportGenerationService generationService,
            CurrentActorProvider actorProvider, ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper, ExternalEndpointPolicy endpointPolicy,
            ExternalHttpClientFactory clientFactory, java.util.concurrent.Executor worker) {
        this(jdbcTemplate, generationService, actorProvider, secretResolver, objectMapper,
                endpointPolicy, clientFactory, worker, new ReportLifecycleService(jdbcTemplate, objectMapper));
    }

    public ReportEnterpriseService(
            JdbcTemplate jdbcTemplate, ReportGenerationService generationService,
            CurrentActorProvider actorProvider, ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper, ExternalEndpointPolicy endpointPolicy,
            ExternalHttpClientFactory clientFactory, java.util.concurrent.Executor worker,
            ReportLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.worker = worker;
        this.jdbcTemplate = jdbcTemplate;
        this.generationService = generationService;
        this.actorProvider = actorProvider;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
        this.clientFactory = clientFactory;
    }

    @Transactional
    public Connection saveConnection(ConnectionCommand command) {
        if (command.provider() != Provider.JIRA && command.provider() != Provider.MEETING_NOTES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "该报告来源类型没有可执行适配器");
        }
        if (command.provider() == Provider.JIRA) {
            ExternalSecretResolver.validateRef(command.secretRef());
            if (command.enabled() && JiraReportSourceClient.projectKeys(command.jiraProjectKeys()).isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "启用 Jira 来源前必须选择项目范围");
            }
        }
        endpointPolicy.validateBaseUrl(command.baseUrl());
        String actorId = actorProvider.currentActor().actorId();
        Connection connection = jdbcTemplate.queryForObject("""
                INSERT INTO report_external_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id, jira_project_keys
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (connection_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    secret_ref = EXCLUDED.secret_ref,
                    jira_project_keys = EXCLUDED.jira_project_keys,
                    enabled = EXCLUDED.enabled,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    updated_at = now()
                RETURNING id, connection_key, display_name, provider, base_url,
                          secret_ref, enabled, owner_actor_id, jira_project_keys::text
                """, this::mapConnection, command.connectionKey().trim(),
                command.displayName().trim(), command.provider().name(),
                trimToNull(command.baseUrl()), trimToNull(command.secretRef()),
                command.enabled(), actorId, json(JiraReportSourceClient.projectKeys(command.jiraProjectKeys())));
        if (!connection.enabled()) {
            jdbcTemplate.update("""
                    UPDATE report_schedules schedule
                    SET enabled = FALSE, claim_token = NULL, claimed_at = NULL, updated_at = now()
                    WHERE schedule.enabled = TRUE
                      AND EXISTS (
                          SELECT 1
                          FROM jsonb_array_elements_text(
                              COALESCE(schedule.source_config -> 'connectionIds', '[]'::jsonb)
                          ) selected(value)
                          WHERE selected.value = ?
                      )
                    """, String.valueOf(connection.id()));
            jdbcTemplate.update("""
                    UPDATE report_schedule_runs run
                    SET status='FAILED', reason='SOURCE_DISABLED', finished_at=now()
                    FROM report_schedules schedule
                    WHERE run.schedule_id=schedule.id AND run.status='RUNNING' AND schedule.enabled=FALSE
                      AND schedule.source_config->'connectionIds' @> ?::jsonb
                    """, "[" + connection.id() + "]");
        }
        return connection;
    }

    public List<Connection> connections() {
        return jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider, base_url,
                       secret_ref, enabled, owner_actor_id, jira_project_keys::text
                FROM report_external_connections ORDER BY display_name
                """, this::mapConnection);
    }

    public ReportDraftResponse generate(GenerateCommand command) {
        return generate(command, null);
    }

    private ReportDraftResponse generate(GenerateCommand command, DueSchedule schedule) {
        String actorId = actorProvider.currentActor().actorId();
        UUID claimToken = claimHandoffs(command.selection().dataHandoffReferences(), actorId);
        try {
            List<RawReportSource> sources = collect(command.selection(), command.period(), actorId, claimToken);
            if (sources.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "生成报告至少需要一条有效来源");
            }
            ReportGenerateRequest request = new ReportGenerateRequest(
                    command.reportType(), command.period(), command.title(),
                    List.of(), List.of(), List.of(), sources,
                    command.templateId(), command.templateVersion());
            ReportPublicationClaim publication = claimToken == null && schedule == null ? null
                    : new ReportPublicationClaim(actorId, claimToken, command.selection().dataHandoffReferences(),
                        schedule == null ? null : schedule.id(), schedule == null ? null : schedule.claimToken(),
                        schedule == null ? null : schedule.runId(), command.selection().previousDataHandoffReference());
            ReportDraftResponse response = publication == null ? generationService.generate(request)
                    : generationService.generate(request, publication);
            if (response.draftId() == null) releaseHandoffs(claimToken, actorId);
            return response;
        } catch (RuntimeException ex) {
            releaseHandoffs(claimToken, actorId);
            throw ex;
        }
    }

    @Transactional
    public Schedule saveSchedule(ScheduleCommand command) {
        requireRepeatableSources(command.selection());
        validateEnabledConnections(command.selection());
        Instant next;
        try {
            CronExpression cron = CronExpression.parse(command.cronExpression().trim());
            ZoneId zone = ZoneId.of(command.zoneId().trim());
            ZonedDateTime nextRun = cron.next(ZonedDateTime.now(zone));
            if (nextRun == null) {
                throw new IllegalArgumentException("cron expression has no future execution time");
            }
            next = nextRun.toInstant();
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "定时表达式或时区无效，请检查 Cron 与 IANA 时区。", ex);
        }
        String actorId = actorProvider.currentActor().actorId();
        String locale = BusinessRequestContextHolder.currentLocale();
        Schedule saved = jdbcTemplate.queryForObject("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, locale, enabled,
                    owner_actor_id, next_run_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (schedule_key) DO UPDATE SET
                    report_type = EXCLUDED.report_type,
                    title_template = EXCLUDED.title_template,
                    cron_expression = EXCLUDED.cron_expression,
                    zone_id = EXCLUDED.zone_id,
                    template_id = EXCLUDED.template_id,
                    template_version = EXCLUDED.template_version,
                    source_config = EXCLUDED.source_config,
                    locale = EXCLUDED.locale,
                    enabled = EXCLUDED.enabled,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    next_run_at = EXCLUDED.next_run_at,
                    claim_token = NULL, claimed_at = NULL,
                    updated_at = now()
                RETURNING id, schedule_key, report_type, title_template,
                          cron_expression, zone_id, locale, enabled, owner_actor_id,
                          last_run_at, next_run_at
                """, this::mapSchedule, command.scheduleKey().trim(),
                command.reportType().name(), command.titleTemplate().trim(),
                command.cronExpression().trim(), command.zoneId().trim(),
                command.templateId().trim(), command.templateVersion().trim(),
                json(command.selection()), locale, command.enabled(), actorId, Timestamp.from(next));
        jdbcTemplate.update("""
                UPDATE report_schedule_runs SET status='FAILED', reason='SCHEDULE_CHANGED', finished_at=now()
                WHERE schedule_id=? AND status='RUNNING'
                """, saved.id());
        return saved;
    }

    public List<Schedule> schedules() {
        return jdbcTemplate.query("""
                SELECT id, schedule_key, report_type, title_template,
                       cron_expression, zone_id, locale, enabled, owner_actor_id,
                       last_run_at, next_run_at
                FROM report_schedules ORDER BY schedule_key
                """, this::mapSchedule);
    }

    public List<ScheduleRun> scheduleRuns(long scheduleId) {
        CurrentActor actor = actorProvider.currentActor();
        return jdbcTemplate.query("""
                SELECT run.id, run.schedule_id, run.report_draft_id, run.status, run.reason,
                       run.started_at, run.finished_at
                FROM report_schedule_runs run
                JOIN report_schedules schedule ON schedule.id = run.schedule_id
                WHERE run.schedule_id = ? AND (schedule.owner_actor_id = ? OR ?)
                ORDER BY run.started_at DESC LIMIT 100
                """, (rs, rowNum) -> new ScheduleRun(
                rs.getLong("id"), rs.getLong("schedule_id"),
                rs.getObject("report_draft_id", Long.class), rs.getString("status"),
                rs.getString("reason"), rs.getTimestamp("started_at").toInstant(),
                instant(rs.getTimestamp("finished_at"))), scheduleId, actor.actorId(),
                actor.hasRole(BusinessRole.ADMIN));
    }

    /** Returns report drafts owned by the current actor so the record tab can continue the lifecycle. */
    public List<ReportRecord> records() {
        String actorId = actorProvider.currentActor().actorId();
        return jdbcTemplate.query("""
                SELECT r.id AS request_id, r.report_type, r.period_start, r.period_end,
                       r.title, r.created_at, d.id AS draft_id, d.status,
                       d.review_reasons, d.expires_at, d.updated_at,
                       review.status AS approval_status
                FROM report_requests r
                JOIN report_drafts d ON d.request_id = r.id
                LEFT JOIN workflow_review_tasks review
                  ON review.subject_type = 'REPORT_DRAFT'
                 AND review.subject_id = d.id::text
                WHERE r.owner_actor_id = ?
                ORDER BY r.created_at DESC
                LIMIT 100
                """, (rs, rowNum) -> new ReportRecord(
                rs.getLong("request_id"), rs.getLong("draft_id"),
                rs.getString("report_type"), rs.getObject("period_start", java.time.LocalDate.class),
                rs.getObject("period_end", java.time.LocalDate.class), rs.getString("title"),
                rs.getString("status"), rs.getString("review_reasons"),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("approval_status")), actorId);
    }

    @Scheduled(fixedDelayString = "${business-copilot.report-copilot.schedule-poll-delay:PT1M}")
    public void generateDueSchedules() {
        try {
            worker.execute(this::processDueSchedules);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // Nothing is claimed until the bounded worker starts.
        }
    }

    public void processDueSchedules() {
        for (int i = 0; i < 20; i++) {
            DueSchedule schedule = claimDueSchedule();
            if (schedule == null) break;
            runSchedule(schedule);
        }
    }

    DueSchedule claimDueSchedule() {
        return lifecycle.claimSchedule();
    }

    private void runSchedule(DueSchedule schedule) {
        ReportPublicationClaim claim = new ReportPublicationClaim(schedule.ownerActorId(), null, List.of(),
                schedule.id(), schedule.claimToken(), schedule.runId());
        BusinessRequestContext previous = BusinessRequestContextHolder.current();
        try {
            ZoneId zone = ZoneId.of(schedule.zoneId());
            LocalDate end = LocalDate.now(zone);
            ReportPeriod period = new ReportPeriod(end.minusDays(6), end, schedule.zoneId());
            BusinessRequestContextHolder.set(new BusinessRequestContext(
                    "report-schedule-" + schedule.runId(), schedule.ownerActorId(), Set.of("OPERATOR"), schedule.locale()));
            ReportDraftResponse response = generate(new GenerateCommand(
                    schedule.reportType(), period, schedule.titleTemplate().replace("{date}", end.toString()),
                    schedule.selection(), schedule.templateId(), schedule.templateVersion()), schedule);
            if (response.draftId() == null) lifecycle.failSchedule(claim, "GENERATION_" + response.status());
        } catch (RuntimeException ex) {
            lifecycle.failSchedule(claim, "SCHEDULE_GENERATION_FAILED");
        } finally {
            if (previous == null) BusinessRequestContextHolder.clear();
            else BusinessRequestContextHolder.set(previous);
        }
    }

    private List<RawReportSource> collect(SourceSelection selection, ReportPeriod period,
                                          String actorId, UUID claimToken) {
        List<RawReportSource> sources = new ArrayList<>();
        validateSelectionShape(selection);
        long externalStarted = System.nanoTime();
        for (Long connectionId : selection.connectionIds()) {
            clientFactory.ensureWithinTaskTimeout(externalStarted);
            Connection connection = requireConnection(connectionId);
            if (!connection.enabled()) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "报告来源已停用，请更新来源选择后重试");
            }
            sources.addAll(loadExternal(connection, period));
        }
        sources.addAll(loadDataHandoffs(selection.dataHandoffReferences(), actorId, claimToken));
        if (selection.includeSupportMetrics()) {
            sources.addAll(loadSupportMetrics(period));
        }
        if (selection.previousDataHandoffReference() != null
                && !selection.dataHandoffReferences().isEmpty()) {
            sources.add(compareDataHandoffs(
                    selection.dataHandoffReferences().getFirst(),
                    selection.previousDataHandoffReference(), actorId, period));
        }
        return List.copyOf(sources);
    }

    private List<RawReportSource> loadExternal(Connection connection, ReportPeriod period) {
        if (connection.provider() == Provider.JIRA) {
            String secret = secretResolver.resolve(connection.secretRef());
            String auth = secret.contains(" ") ? secret : "Bearer " + secret;
            return new JiraReportSourceClient(clientFactory).collect(connection, period, auth);
        }
        if (connection.provider() == Provider.MEETING_NOTES) {
            JsonNode response = clientFactory.validatePayload(
                    clientFactory.builder(connection.baseUrl()).build().get()
                    .uri(trimSlash(connection.baseUrl()) + "/notes?from="
                            + period.periodStart() + "&to=" + period.periodEnd())
                    .retrieve().body(JsonNode.class));
            List<RawReportSource> sources = new ArrayList<>();
            for (JsonNode note : iterable(response == null ? null : response.path("items"))) {
                sources.add(raw(ReportSourceType.MEETING_NOTE,
                        note.path("title").asText("会议纪要"),
                        note.path("content").asText(""),
                        connection.connectionKey(), note.path("updatedAt").asText(null), ""));
            }
            return sources;
        }
        return List.of();
    }

    private List<RawReportSource> loadDataHandoffs(List<String> references, String actorId,
                                                   UUID claimToken) {
        List<RawReportSource> sources = new ArrayList<>();
        for (String reference : references) {
            List<DataHandoffRow> rows = jdbcTemplate.query("""
                    SELECT handoff.title, handoff.source_reference, result.rows_json::text,
                           result.explanation_json::text, result.created_at, result.row_count,
                           result.expires_at, result.truncated, handoff.metric_snapshot::text
                    FROM data_report_handoffs handoff
                    JOIN data_query_results result ON result.id = handoff.query_result_id
                    WHERE handoff.source_reference = ? AND handoff.status = 'CLAIMED'
                      AND handoff.owner_actor_id = ? AND handoff.claim_token = ?
                      AND result.expires_at > now()
                    """, (rs, rowNum) -> new DataHandoffRow(
                    rs.getString("title"), rs.getString("source_reference"),
                    rs.getString("rows_json"), rs.getString("explanation_json"),
                    rs.getInt("row_count"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                    rs.getBoolean("truncated"), objectMapper.readValue(rs.getString("metric_snapshot"),
                    new TypeReference<Map<String, String>>() { })), reference, actorId, claimToken);
            if (rows.isEmpty()) {
                // REP-03：来源采集失败必须明确列出缺失来源；报告不生成，绝不静默输出看似完整的结果。
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "以下 Data 结果交接采集失败（已消费、过期或无权限），报告未生成，"
                                + "受影响结论全部缺失：" + reference);
            }
            rows.forEach(row -> sources.addAll(normalizeDataHandoff(row)));
        }
        return sources;
    }

    private List<RawReportSource> normalizeDataHandoff(DataHandoffRow row) {
        List<RawReportSource> sources = new ArrayList<>();
        Instant validUntil = row.expiresAt();
        Map<String, String> provenance = new java.util.LinkedHashMap<>(row.metric());
        provenance.put("rowCount", String.valueOf(row.rowCount()));
        provenance.put("completeness", row.truncated() ? "TRUNCATED" : "COMPLETE_QUERY_RESULT");
        provenance.put("sourceReference", row.sourceReference());
        sources.add(new RawReportSource(
                ReportSourceType.KNOWLEDGE, row.title(), (row.truncated() ? "来源已截断，仅代表返回的部分数据，不可用于总量或环比。\n" : "") + row.rowsJson()
                        + (row.explanationJson() == null ? "" : "\n" + row.explanationJson()),
                provenance,
                "data-copilot", row.sourceReference(), row.createdAt(),
                "Asia/Shanghai", "query-result", validUntil));
        if (row.truncated()) return sources;
        if ("period-total-v1".equals(row.metric().get("schema"))) {
            var attributes = new java.util.LinkedHashMap<>(provenance);
            attributes.put("name", row.metric().get("metricKey"));
            sources.add(new RawReportSource(ReportSourceType.METRIC, row.metric().get("metricName"),
                    "已人工核验 SQL 及业务周期的期间总量：" + objectMapper.writeValueAsString(row.metric()),
                    attributes, "data-copilot", row.sourceReference(), row.createdAt(),
                    row.metric().get("timezone"), row.metric().get("unit"), validUntil));
            return sources;
        }
        try {
            JsonNode root = objectMapper.readTree(row.rowsJson());
            if (!root.isArray() || root.size() != 1 || !root.get(0).isObject()) return sources;
            root.get(0).properties().stream()
                    .filter(entry -> entry.getValue().isNumber())
                    .limit(20)
                    .forEach(entry -> {
                        String metricName = row.title() + "." + entry.getKey();
                        String metricValue = entry.getValue().asText();
                        String unit = "query-result";
                        sources.add(new RawReportSource(
                                ReportSourceType.METRIC, metricName,
                                "name=" + metricName + "\nvalue=" + metricValue + "\nunit=" + unit,
                                Map.of("name", metricName, "value", metricValue, "unit", unit),
                                "data-copilot", row.sourceReference(), row.createdAt(),
                                "Asia/Shanghai", unit, validUntil));
                    });
        } catch (JacksonException ignored) {
            // The sanitized full result remains available as KNOWLEDGE evidence.
        }
        return sources;
    }

    private List<RawReportSource> loadSupportMetrics(ReportPeriod period) {
        ZoneId zone = ZoneId.of(period.timezone());
        Instant start = period.periodStart().atStartOfDay(zone).toInstant();
        Instant end = period.periodEnd().plusDays(1).atStartOfDay(zone).toInstant();
        Instant observedAt = Instant.now();
        Map<String, Object> metrics = jdbcTemplate.queryForMap("""
                SELECT
                    COUNT(*) FILTER (WHERE created_at >= ? AND created_at < ?) AS total,
                    (SELECT COUNT(DISTINCT ticket_id) FROM support_audit_logs
                     WHERE event_type = 'CUSTOMER_REPLY_RECORDED'
                       AND created_at >= ? AND created_at < ?) AS closed,
                    COUNT(*) FILTER (WHERE status NOT IN ('CLOSED', 'CANCELED')) AS backlog,
                    COUNT(*) FILTER (WHERE status = 'NEEDS_HUMAN') AS handed_off,
                    COUNT(*) FILTER (WHERE status NOT IN ('CLOSED', 'CANCELED')
                                     AND sla_status = 'AT_RISK') AS sla_at_risk,
                    COUNT(*) FILTER (WHERE status NOT IN ('CLOSED', 'CANCELED')
                                     AND sla_status = 'BREACHED') AS sla_breached
                FROM support_tickets
                """, Timestamp.from(start), Timestamp.from(end), Timestamp.from(start), Timestamp.from(end));
        Map<String, String> definitions = Map.of(
                "total", "期间创建工单数", "closed", "期间有人工回复记录的去重工单数（仅保留的审计事件）",
                "backlog", "采集时未关闭工单存量", "handed_off", "采集时待人工处理工单存量",
                "sla_at_risk", "采集时临近 SLA 的未关闭工单存量",
                "sla_breached", "采集时超过 SLA 的未关闭工单存量");
        return metrics.entrySet().stream().map(entry -> {
            boolean flow = entry.getKey().equals("total") || entry.getKey().equals("closed");
            String name = "support." + entry.getKey();
            String definition = definitions.get(entry.getKey());
            Map<String, String> attributes = new java.util.LinkedHashMap<>();
            attributes.put("name", name);
            attributes.put("value", String.valueOf(entry.getValue()));
            attributes.put("unit", "tickets");
            attributes.put("metricType", flow ? "PERIOD" : "SNAPSHOT");
            attributes.put("definition", definition);
            attributes.put("definitionVersion", "support-metrics-v2");
            attributes.put("periodStart", start.toString());
            attributes.put("periodEndExclusive", end.toString());
            attributes.put("timezone", period.timezone());
            attributes.put("collectedAt", observedAt.toString());
            attributes.put("completeness", entry.getKey().equals("closed") ? "RECORDED_EVENTS_ONLY" : "PLATFORM_RECORDS");
            return new RawReportSource(ReportSourceType.METRIC, definition,
                    "name=" + name + "\nvalue=" + entry.getValue() + "\nunit=tickets\n口径=" + definition
                            + (flow ? "；周期=[" + start + ", " + end + ")" : "；时点=" + observedAt),
                    Map.copyOf(attributes), "support-copilot", "support-metrics-v2", observedAt,
                    period.timezone(), "tickets", observedAt.plus(java.time.Duration.ofDays(1)));
        }).toList();
    }

    private RawReportSource compareDataHandoffs(String current, String previous, String actorId, ReportPeriod period) {
        Map<String, String> currentMetric = handoffMetric(current, actorId);
        Map<String, String> previousMetric = handoffMetric(previous, actorId);
        return new DataMetricComparison().compare(currentMetric, previousMetric, current, previous, period);
    }

    private Map<String, String> handoffMetric(String reference, String actorId) {
        var rows = jdbcTemplate.query("""
                SELECT handoff.metric_snapshot::text, result.truncated, result.expires_at
                FROM data_report_handoffs handoff
                JOIN data_query_results result ON result.id = handoff.query_result_id
                WHERE handoff.source_reference = ? AND handoff.owner_actor_id = ?
                  AND result.expires_at > now()
                """, (rs, rowNum) -> {
            Map<String, String> metric = new java.util.LinkedHashMap<>(objectMapper.readValue(
                    rs.getString("metric_snapshot"), new TypeReference<Map<String, String>>() { }));
            metric.put("truncated", String.valueOf(rs.getBoolean("truncated")));
            metric.put("validUntil", rs.getTimestamp("expires_at").toInstant().toString());
            return Map.copyOf(metric);
        }, reference, actorId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "对比来源已过期或不可访问");
        return rows.getFirst();
    }

    private UUID claimHandoffs(List<String> references, String actorId) {
        if (references.isEmpty()) return null;
        UUID claimToken = UUID.randomUUID();
        for (String reference : references.stream().distinct().toList()) {
            int updated = jdbcTemplate.update("""
                    UPDATE data_report_handoffs handoff
                    SET status = 'CLAIMED', claim_token = ?, claimed_at = now()
                    FROM data_query_results result
                    WHERE handoff.query_result_id = result.id
                      AND handoff.source_reference = ? AND handoff.owner_actor_id = ?
                      AND (handoff.status = 'READY'
                           OR (handoff.status = 'CLAIMED'
                               AND handoff.claimed_at < now() - interval '15 minutes'))
                      AND result.expires_at > now()
                    """, claimToken, reference, actorId);
            if (updated != 1) {
                releaseHandoffs(claimToken, actorId);
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "Data 结果交接已被消费、过期或正在生成报告");
            }
        }
        return claimToken;
    }

    private void releaseHandoffs(UUID claimToken, String actorId) {
        if (claimToken == null) return;
        jdbcTemplate.update("""
                UPDATE data_report_handoffs
                SET status = 'READY', claim_token = NULL, claimed_at = NULL
                WHERE claim_token = ? AND owner_actor_id = ? AND status = 'CLAIMED'
                """, claimToken, actorId);
    }

    /** DATA-05：读取草稿的数据追溯链；链接由交接消费时写入，未消费的交接不产生链接。 */
    public List<DataTraceLink> dataTraceability(long draftId) {
        CurrentActor actor = actorProvider.currentActor();
        Integer visible = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM report_drafts
                WHERE id = ? AND (? OR owner_actor_id = ?)
                """, Integer.class, draftId, actor.hasRole(BusinessRole.ADMIN), actor.actorId());
        if (visible == null || visible == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return jdbcTemplate.query("""
                SELECT draft_id, source_reference, query_result_id, candidate_id, handoff_id, linked_at
                FROM report_draft_data_links
                WHERE draft_id = ?
                ORDER BY linked_at, id
                """, (rs, rowNum) -> new DataTraceLink(
                rs.getLong("draft_id"),
                rs.getString("source_reference"),
                rs.getObject("query_result_id", Long.class),
                rs.getString("candidate_id"),
                rs.getObject("handoff_id", Long.class),
                rs.getTimestamp("linked_at").toInstant()), draftId);
    }

    public record DataTraceLink(long draftId, String sourceReference, Long queryResultId,
                                String candidateId, Long handoffId, Instant linkedAt) {
    }

    private void requireRepeatableSources(SourceSelection selection) {
        validateSelectionShape(selection);
        if (!selection.dataHandoffReferences().isEmpty()
                || (selection.previousDataHandoffReference() != null
                    && !selection.previousDataHandoffReference().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "定时报告不能使用一次性 Data 结果交接，请配置可重复读取的外部来源或客服指标");
        }
        if (selection.connectionIds().isEmpty() && !selection.includeSupportMetrics()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "定时报告必须至少配置一个可重复读取的有效来源");
        }
    }

    private void validateSelectionShape(SourceSelection selection) {
        if (selection == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "报告来源配置不能为空");
        }
        if (selection.connectionIds().size() > 20
                || selection.connectionIds().stream().anyMatch(id -> id == null || id <= 0)
                || selection.connectionIds().stream().distinct().count()
                    != selection.connectionIds().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "报告外部来源最多 20 个，且必须是唯一的有效连接");
        }
    }

    private void validateEnabledConnections(SourceSelection selection) {
        for (Long connectionId : selection.connectionIds()) {
            Connection connection = requireConnection(connectionId);
            if (!connection.enabled()
                    || (connection.provider() != Provider.JIRA
                        && connection.provider() != Provider.MEETING_NOTES)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "定时报告只能引用已启用且具有可执行适配器的来源");
            }
        }
    }

    private RawReportSource raw(ReportSourceType type, String title, String content,
                                String provider, String version, String unit) {
        return new RawReportSource(type, title, content, Map.of(),
                provider, version, Instant.now(), "Asia/Shanghai", unit,
                Instant.now().plus(java.time.Duration.ofDays(7)));
    }

    private RawReportSource metric(String title, String name, Object value, String unit,
                                   String provider, Instant observedAt) {
        String metricValue = String.valueOf(value);
        return new RawReportSource(ReportSourceType.METRIC, title,
                "name=" + name + "\nvalue=" + metricValue + "\nunit=" + unit,
                Map.of("name", name, "value", metricValue, "unit", unit),
                provider, observedAt.toString(), observedAt,
                "Asia/Shanghai", unit, observedAt.plus(java.time.Duration.ofDays(7)));
    }

    private Connection requireConnection(long id) {
        List<Connection> rows = jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider, base_url,
                       secret_ref, enabled, owner_actor_id, jira_project_keys::text
                FROM report_external_connections WHERE id = ?
                """, this::mapConnection, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private Connection mapConnection(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Connection(rs.getLong("id"), rs.getString("connection_key"),
                rs.getString("display_name"), Provider.valueOf(rs.getString("provider")),
                rs.getString("base_url"), rs.getString("secret_ref"),
                rs.getBoolean("enabled"), rs.getString("owner_actor_id"),
                objectMapper.readValue(rs.getString("jira_project_keys"), new TypeReference<List<String>>() { }));
    }

    private Schedule mapSchedule(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Schedule(rs.getLong("id"), rs.getString("schedule_key"),
                ReportType.valueOf(rs.getString("report_type")), rs.getString("title_template"),
                rs.getString("cron_expression"), rs.getString("zone_id"),
                rs.getString("locale"), rs.getBoolean("enabled"), rs.getString("owner_actor_id"),
                instant(rs.getTimestamp("last_run_at")), instant(rs.getTimestamp("next_run_at")));
    }

    private Iterable<JsonNode> iterable(JsonNode value) {
        return value != null && value.isArray() ? value : List.of();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("报告企业来源序列化失败", ex);
        }
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public enum Provider { JIRA, MEETING_NOTES, DATA_QUERY, SUPPORT_METRICS }
    public record ConnectionCommand(String connectionKey, String displayName, Provider provider,
                                    String baseUrl, String secretRef, boolean enabled, List<String> jiraProjectKeys) {
        public ConnectionCommand(String connectionKey, String displayName, Provider provider, String baseUrl,
                                 String secretRef, boolean enabled) {
            this(connectionKey, displayName, provider, baseUrl, secretRef, enabled, List.of());
        }
    }
    public record Connection(long id, String connectionKey, String displayName, Provider provider,
                             String baseUrl, String secretRef, boolean enabled, String ownerActorId, List<String> jiraProjectKeys) { }
    public record SourceSelection(List<Long> connectionIds, List<String> dataHandoffReferences,
                                  boolean includeSupportMetrics, String previousDataHandoffReference) {
        public SourceSelection {
            connectionIds = connectionIds == null ? List.of() : List.copyOf(connectionIds);
            dataHandoffReferences = dataHandoffReferences == null
                    ? List.of() : List.copyOf(dataHandoffReferences);
        }
    }
    public record GenerateCommand(ReportType reportType, ReportPeriod period, String title,
                                  SourceSelection selection, String templateId,
                                  String templateVersion) { }
    public record ScheduleCommand(String scheduleKey, ReportType reportType, String titleTemplate,
                                  String cronExpression, String zoneId, String templateId,
                                  String templateVersion, SourceSelection selection,
                                  boolean enabled) { }
    public record Schedule(long id, String scheduleKey, ReportType reportType, String titleTemplate,
                           String cronExpression, String zoneId, String locale, boolean enabled,
                           String ownerActorId, Instant lastRunAt, Instant nextRunAt) { }
    public record ScheduleRun(long id, long scheduleId, Long draftId, String status,
                              String reason, Instant startedAt, Instant finishedAt) { }
    public record ReportRecord(long requestId, long draftId, String reportType,
                               java.time.LocalDate periodStart, java.time.LocalDate periodEnd,
                               String title, String status, String reviewReasons,
                               Instant expiresAt, Instant createdAt, Instant updatedAt,
                               String approvalStatus) { }
    record DueSchedule(long id, String scheduleKey, ReportType reportType,
                               String titleTemplate, String cronExpression, String zoneId,
                               String templateId, String templateVersion,
                               SourceSelection selection, String locale,
                               String ownerActorId, UUID claimToken, long runId) { }
    private record DataHandoffRow(String title, String sourceReference, String rowsJson,
                                  String explanationJson, int rowCount, Instant createdAt, Instant expiresAt,
                                  boolean truncated, Map<String, String> metric) { }
}
