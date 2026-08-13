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

    public ReportEnterpriseService(
            JdbcTemplate jdbcTemplate,
            ReportGenerationService generationService,
            CurrentActorProvider actorProvider,
            ExternalSecretResolver secretResolver,
            ObjectMapper objectMapper,
            ExternalEndpointPolicy endpointPolicy,
            ExternalHttpClientFactory clientFactory) {
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
        }
        endpointPolicy.validateBaseUrl(command.baseUrl());
        String actorId = actorProvider.currentActor().actorId();
        Connection connection = jdbcTemplate.queryForObject("""
                INSERT INTO report_external_connections (
                    connection_key, display_name, provider, base_url, secret_ref,
                    enabled, owner_actor_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (connection_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    secret_ref = EXCLUDED.secret_ref,
                    enabled = EXCLUDED.enabled,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    updated_at = now()
                RETURNING id, connection_key, display_name, provider, base_url,
                          secret_ref, enabled, owner_actor_id
                """, this::mapConnection, command.connectionKey().trim(),
                command.displayName().trim(), command.provider().name(),
                trimToNull(command.baseUrl()), trimToNull(command.secretRef()),
                command.enabled(), actorId);
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
        }
        return connection;
    }

    public List<Connection> connections() {
        return jdbcTemplate.query("""
                SELECT id, connection_key, display_name, provider, base_url,
                       secret_ref, enabled, owner_actor_id
                FROM report_external_connections ORDER BY display_name
                """, this::mapConnection);
    }

    public ReportDraftResponse generate(GenerateCommand command) {
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
            ReportDraftResponse response = generationService.generate(request);
            if ("DRAFTED".equals(response.status()) && response.content() != null) {
                consumeHandoffs(claimToken, actorId);
            } else {
                releaseHandoffs(claimToken, actorId);
            }
            return response;
        } catch (RuntimeException ex) {
            releaseHandoffs(claimToken, actorId);
            throw ex;
        }
    }

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
        return jdbcTemplate.queryForObject("""
                INSERT INTO report_schedules (
                    schedule_key, report_type, title_template, cron_expression, zone_id,
                    template_id, template_version, source_config, enabled,
                    owner_actor_id, next_run_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (schedule_key) DO UPDATE SET
                    report_type = EXCLUDED.report_type,
                    title_template = EXCLUDED.title_template,
                    cron_expression = EXCLUDED.cron_expression,
                    zone_id = EXCLUDED.zone_id,
                    template_id = EXCLUDED.template_id,
                    template_version = EXCLUDED.template_version,
                    source_config = EXCLUDED.source_config,
                    enabled = EXCLUDED.enabled,
                    owner_actor_id = EXCLUDED.owner_actor_id,
                    next_run_at = EXCLUDED.next_run_at,
                    updated_at = now()
                RETURNING id, schedule_key, report_type, title_template,
                          cron_expression, zone_id, enabled, owner_actor_id,
                          last_run_at, next_run_at
                """, this::mapSchedule, command.scheduleKey().trim(),
                command.reportType().name(), command.titleTemplate().trim(),
                command.cronExpression().trim(), command.zoneId().trim(),
                command.templateId().trim(), command.templateVersion().trim(),
                json(command.selection()), command.enabled(), actorId, Timestamp.from(next));
    }

    public List<Schedule> schedules() {
        return jdbcTemplate.query("""
                SELECT id, schedule_key, report_type, title_template,
                       cron_expression, zone_id, enabled, owner_actor_id,
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
                       d.review_reasons, d.expires_at, d.updated_at
                FROM report_requests r
                JOIN report_drafts d ON d.request_id = r.id
                WHERE r.owner_actor_id = ?
                ORDER BY r.created_at DESC
                LIMIT 100
                """, (rs, rowNum) -> new ReportRecord(
                rs.getLong("request_id"), rs.getLong("draft_id"),
                rs.getString("report_type"), rs.getObject("period_start", java.time.LocalDate.class),
                rs.getObject("period_end", java.time.LocalDate.class), rs.getString("title"),
                rs.getString("status"), rs.getString("review_reasons"),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), actorId);
    }

    @Scheduled(fixedDelayString = "${business-copilot.report-copilot.schedule-poll-delay:PT1M}")
    public void generateDueSchedules() {
        for (int i = 0; i < 20; i++) {
            DueSchedule schedule = claimDueSchedule();
            if (schedule == null) break;
            runSchedule(schedule);
        }
    }

    @Transactional
    DueSchedule claimDueSchedule() {
        jdbcTemplate.update("""
                UPDATE report_schedule_runs run
                SET status = 'FAILED', reason = 'SCHEDULE_LEASE_EXPIRED', finished_at = now()
                FROM report_schedules schedule
                WHERE run.schedule_id = schedule.id
                  AND run.status = 'RUNNING'
                  AND schedule.claimed_at < now() - interval '15 minutes'
                """);
        UUID claimToken = UUID.randomUUID();
        List<DueSchedule> rows = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id FROM report_schedules
                    WHERE enabled = TRUE AND next_run_at <= now()
                      AND (claimed_at IS NULL OR claimed_at < now() - interval '15 minutes')
                    ORDER BY next_run_at
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE report_schedules schedule
                SET claim_token = ?, claimed_at = now(), updated_at = now()
                FROM candidate WHERE schedule.id = candidate.id
                RETURNING schedule.id, schedule.schedule_key, schedule.report_type,
                          schedule.title_template, schedule.cron_expression, schedule.zone_id,
                          schedule.template_id, schedule.template_version,
                          schedule.source_config::text, schedule.owner_actor_id,
                          schedule.claim_token
                """, this::mapDueSchedule, claimToken);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void runSchedule(DueSchedule schedule) {
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO report_schedule_runs (schedule_id, status)
                VALUES (?, 'RUNNING') RETURNING id
                """, Long.class, schedule.id());
        ZoneId zone = ZoneId.of(schedule.zoneId());
        LocalDate end = LocalDate.now(zone);
        ReportPeriod period = new ReportPeriod(end.minusDays(6), end);
        try {
            BusinessRequestContextHolder.set(new BusinessRequestContext(
                    "report-schedule-" + runId, schedule.ownerActorId(), Set.of("OPERATOR")));
            ReportDraftResponse response = generate(new GenerateCommand(
                    schedule.reportType(), period,
                    schedule.titleTemplate().replace("{date}", end.toString()),
                    schedule.selection(), schedule.templateId(), schedule.templateVersion()));
            String runStatus = switch (response.status()) {
                case "DRAFTED" -> "DRAFTED";
                case "NEEDS_REVIEW" -> "NEEDS_REVIEW";
                default -> "FAILED";
            };
            String reason = "FAILED".equals(runStatus)
                    ? "GENERATION_" + response.status() : null;
            jdbcTemplate.update("""
                    UPDATE report_schedule_runs
                    SET status = ?, reason = ?, report_draft_id = ?, finished_at = now()
                    WHERE id = ?
                    """, runStatus, reason, response.draftId(), runId);
            updateNextRun(schedule, zone);
        } catch (RuntimeException ex) {
            jdbcTemplate.update("""
                    UPDATE report_schedule_runs
                    SET status = 'FAILED', reason = 'SCHEDULE_GENERATION_FAILED',
                        finished_at = now()
                    WHERE id = ?
                    """, runId);
            updateNextRun(schedule, zone);
        } finally {
            BusinessRequestContextHolder.clear();
        }
    }

    private void updateNextRun(DueSchedule schedule, ZoneId zone) {
        ZonedDateTime nextRun;
        try {
            nextRun = CronExpression.parse(schedule.cronExpression())
                    .next(ZonedDateTime.now(zone));
        } catch (IllegalArgumentException | DateTimeException ex) {
            nextRun = null;
        }
        if (nextRun == null) {
            jdbcTemplate.update("""
                    UPDATE report_schedules
                    SET enabled = FALSE, last_run_at = now(), claim_token = NULL,
                        claimed_at = NULL, updated_at = now()
                    WHERE id = ? AND claim_token = ?
                    """, schedule.id(), schedule.claimToken());
            return;
        }
        Instant next = nextRun.toInstant();
        jdbcTemplate.update("""
                UPDATE report_schedules
                SET last_run_at = now(), next_run_at = ?, claim_token = NULL,
                    claimed_at = NULL, updated_at = now()
                WHERE id = ? AND claim_token = ?
                """, Timestamp.from(next), schedule.id(), schedule.claimToken());
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
            sources.addAll(loadSupportMetrics());
        }
        if (selection.previousDataHandoffReference() != null
                && !selection.dataHandoffReferences().isEmpty()) {
            sources.add(compareDataHandoffs(
                    selection.dataHandoffReferences().getFirst(),
                    selection.previousDataHandoffReference(), actorId));
        }
        return List.copyOf(sources);
    }

    private List<RawReportSource> loadExternal(Connection connection, ReportPeriod period) {
        if (connection.provider() == Provider.JIRA) {
            String secret = secretResolver.resolve(connection.secretRef());
            String auth = secret.contains(" ") ? secret : "Bearer " + secret;
            RestClient client = clientFactory.builder(connection.baseUrl())
                    .defaultHeader("Authorization", auth).build();
            JsonNode response = clientFactory.validatePayload(client.get()
                    .uri(trimSlash(connection.baseUrl())
                    + "/rest/api/3/search?jql=updated%20%3E%3D%20"
                    + period.periodStart() + "&maxResults=100")
                    .retrieve().body(JsonNode.class));
            List<RawReportSource> sources = new ArrayList<>();
            for (JsonNode issue : iterable(response == null ? null : response.path("issues"))) {
                JsonNode fields = issue.path("fields");
                String key = issue.path("key").asText();
                String summary = fields.path("summary").asText("");
                String status = fields.path("status").path("name").asText("");
                sources.add(raw(ReportSourceType.TASK, key + " " + summary,
                        "状态：" + status + "；" + summary,
                        connection.connectionKey(), fields.path("updated").asText(null), ""));
            }
            return sources;
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
                           result.created_at, result.row_count
                    FROM data_report_handoffs handoff
                    JOIN data_query_results result ON result.id = handoff.query_result_id
                    WHERE handoff.source_reference = ? AND handoff.status = 'CLAIMED'
                      AND handoff.owner_actor_id = ? AND handoff.claim_token = ?
                      AND result.expires_at > now()
                    """, (rs, rowNum) -> new DataHandoffRow(
                    rs.getString("title"), rs.getString("source_reference"),
                    rs.getString("rows_json"), rs.getInt("row_count"),
                    rs.getTimestamp("created_at").toInstant()), reference, actorId, claimToken);
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            rows.forEach(row -> sources.addAll(normalizeDataHandoff(row)));
        }
        return sources;
    }

    private List<RawReportSource> normalizeDataHandoff(DataHandoffRow row) {
        List<RawReportSource> sources = new ArrayList<>();
        Instant validUntil = row.createdAt().plus(java.time.Duration.ofDays(1));
        sources.add(new RawReportSource(
                ReportSourceType.KNOWLEDGE, row.title(), row.rowsJson(),
                Map.of("rowCount", String.valueOf(row.rowCount())),
                "data-copilot", row.sourceReference(), row.createdAt(),
                "Asia/Shanghai", "query-result", validUntil));
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

    private List<RawReportSource> loadSupportMetrics() {
        Map<String, Object> metrics = jdbcTemplate.queryForMap("""
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE status = 'CLOSED') AS closed,
                    COUNT(*) FILTER (WHERE status = 'NEEDS_HUMAN') AS handed_off,
                    COUNT(*) FILTER (WHERE sla_status = 'AT_RISK') AS sla_at_risk,
                    COUNT(*) FILTER (WHERE sla_status = 'BREACHED') AS sla_breached
                FROM support_tickets
                """);
        Instant observedAt = Instant.now();
        return metrics.entrySet().stream()
                .map(entry -> metric("客服质量统计 · " + entry.getKey(),
                        "support." + entry.getKey(), entry.getValue(), "tickets",
                        "support-copilot", observedAt))
                .toList();
    }

    private RawReportSource compareDataHandoffs(String current, String previous, String actorId) {
        Integer currentRows = handoffRows(current, actorId);
        Integer previousRows = handoffRows(previous, actorId);
        double change = previousRows == null || previousRows == 0
                ? 0 : ((double) currentRows - previousRows) / previousRows * 100;
        String severity = Math.abs(change) >= 20 ? "需要复核" : "正常";
        Instant observedAt = Instant.now();
        return new RawReportSource(ReportSourceType.METRIC, "环比差异与来源异常",
                "当前结果行数=" + currentRows + "，对比期行数=" + previousRows
                        + "，变化=" + String.format(java.util.Locale.ROOT, "%.2f%%", change)
                        + "，状态=" + severity,
                Map.of("name", "data.rowCount.change", "value",
                        String.format(java.util.Locale.ROOT, "%.2f", change), "unit", "percent"),
                "report-difference", observedAt.toString(), observedAt,
                "Asia/Shanghai", "percent", observedAt.plus(java.time.Duration.ofDays(7)));
    }

    private Integer handoffRows(String reference, String actorId) {
        List<Integer> rows = jdbcTemplate.query("""
                SELECT result.row_count
                FROM data_report_handoffs handoff
                JOIN data_query_results result ON result.id = handoff.query_result_id
                WHERE handoff.source_reference = ? AND handoff.owner_actor_id = ?
                  AND result.expires_at > now()
                """, (rs, rowNum) -> rs.getInt(1), reference, actorId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
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

    private void consumeHandoffs(UUID claimToken, String actorId) {
        if (claimToken == null) return;
        jdbcTemplate.update("""
                UPDATE data_report_handoffs
                SET status = 'CONSUMED', consumed_at = now(), claim_token = NULL, claimed_at = NULL
                WHERE claim_token = ? AND owner_actor_id = ? AND status = 'CLAIMED'
                """, claimToken, actorId);
    }

    private void releaseHandoffs(UUID claimToken, String actorId) {
        if (claimToken == null) return;
        jdbcTemplate.update("""
                UPDATE data_report_handoffs
                SET status = 'READY', claim_token = NULL, claimed_at = NULL
                WHERE claim_token = ? AND owner_actor_id = ? AND status = 'CLAIMED'
                """, claimToken, actorId);
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
                       secret_ref, enabled, owner_actor_id
                FROM report_external_connections WHERE id = ?
                """, this::mapConnection, id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private Connection mapConnection(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Connection(rs.getLong("id"), rs.getString("connection_key"),
                rs.getString("display_name"), Provider.valueOf(rs.getString("provider")),
                rs.getString("base_url"), rs.getString("secret_ref"),
                rs.getBoolean("enabled"), rs.getString("owner_actor_id"));
    }

    private Schedule mapSchedule(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Schedule(rs.getLong("id"), rs.getString("schedule_key"),
                ReportType.valueOf(rs.getString("report_type")), rs.getString("title_template"),
                rs.getString("cron_expression"), rs.getString("zone_id"),
                rs.getBoolean("enabled"), rs.getString("owner_actor_id"),
                instant(rs.getTimestamp("last_run_at")), instant(rs.getTimestamp("next_run_at")));
    }

    private DueSchedule mapDueSchedule(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        try {
            return new DueSchedule(rs.getLong("id"), rs.getString("schedule_key"),
                    ReportType.valueOf(rs.getString("report_type")), rs.getString("title_template"),
                    rs.getString("cron_expression"), rs.getString("zone_id"),
                    rs.getString("template_id"), rs.getString("template_version"),
                    objectMapper.readValue(rs.getString("source_config"), SourceSelection.class),
                    rs.getString("owner_actor_id"), rs.getObject("claim_token", UUID.class));
        } catch (JacksonException ex) {
            throw new IllegalStateException("报告定时来源配置读取失败", ex);
        }
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
                                    String baseUrl, String secretRef, boolean enabled) { }
    public record Connection(long id, String connectionKey, String displayName, Provider provider,
                             String baseUrl, String secretRef, boolean enabled, String ownerActorId) { }
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
                           String cronExpression, String zoneId, boolean enabled,
                           String ownerActorId, Instant lastRunAt, Instant nextRunAt) { }
    public record ScheduleRun(long id, long scheduleId, Long draftId, String status,
                              String reason, Instant startedAt, Instant finishedAt) { }
    public record ReportRecord(long requestId, long draftId, String reportType,
                               java.time.LocalDate periodStart, java.time.LocalDate periodEnd,
                               String title, String status, String reviewReasons,
                               Instant expiresAt, Instant createdAt, Instant updatedAt) { }
    private record DueSchedule(long id, String scheduleKey, ReportType reportType,
                               String titleTemplate, String cronExpression, String zoneId,
                               String templateId, String templateVersion,
                               SourceSelection selection, String ownerActorId, UUID claimToken) { }
    private record DataHandoffRow(String title, String sourceReference, String rowsJson,
                                  int rowCount, Instant createdAt) { }
}
