package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.aicore.AiCallCoordinator;
import dev.qcoding.businesscopilot.aicore.AiModelProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.KnowledgeCopilotProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 私有管理台的只读技术诊断聚合，不读取 Key、Prompt 正文或业务正文。 */
@Service
public class AdminDiagnosticsService {

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final RuntimeModeProperties runtimeModeProperties;
    private final PublicDemoProperties publicDemoProperties;
    private final AiModelProperties aiModelProperties;
    private final KnowledgeCopilotProperties knowledgeProperties;
    private final AiCallCoordinator aiCallCoordinator;
    private final DemoScenarioRepository scenarioRepository;

    public AdminDiagnosticsService(
            JdbcTemplate jdbcTemplate,
            Environment environment,
            RuntimeModeProperties runtimeModeProperties,
            PublicDemoProperties publicDemoProperties,
            AiModelProperties aiModelProperties,
            KnowledgeCopilotProperties knowledgeProperties,
            AiCallCoordinator aiCallCoordinator,
            DemoScenarioRepository scenarioRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.runtimeModeProperties = runtimeModeProperties;
        this.publicDemoProperties = publicDemoProperties;
        this.aiModelProperties = aiModelProperties;
        this.knowledgeProperties = knowledgeProperties;
        this.aiCallCoordinator = aiCallCoordinator;
        this.scenarioRepository = scenarioRepository;
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(
                Instant.now(),
                runtimeModeProperties.mode().propertyValue(),
                moduleHealth(),
                scenarioRepository.countEnabled(),
                groupedCounts("knowledge_documents", "index_status"),
                groupedCounts("knowledge_documents", "visibility_scope"),
                new ModelView(
                        aiModelProperties.providerName(),
                        aiModelProperties.modelName(),
                        knowledgeProperties.embeddingModelName(),
                        knowledgeProperties.embeddingDimension()),
                promptHashes(),
                usage(),
                aiCallCoordinator.diagnostics(),
                new LimitView(
                        publicDemoProperties.clientDailyOperations(),
                        publicDemoProperties.globalDailyModelCalls(),
                        publicDemoProperties.maxConcurrentExecutions()),
                enterpriseExpansion(),
                recentDemoJobs(),
                auditSummary());
    }

    private Map<String, Boolean> moduleHealth() {
        Map<String, Boolean> health = new LinkedHashMap<>();
        health.put("企业知识助手", bool("business-copilot.knowledge.enabled", true));
        health.put("客服工作台", bool("business-copilot.support-copilot.enabled", true));
        health.put("HR Copilot", bool("business-copilot.resume-copilot.enabled", true));
        health.put("数据分析助手", true);
        health.put("报告生成助手", bool("business-copilot.report-copilot.enabled", true));
        return health;
    }

    private Map<String, Long> groupedCounts(String table, String column) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT " + column + ", COUNT(*) AS total FROM " + table
                        + " GROUP BY " + column + " ORDER BY " + column)
                .forEach(row -> counts.put(
                        String.valueOf(row.get(column)),
                        ((Number) row.get("total")).longValue()));
        return counts;
    }

    private List<PromptView> promptHashes() {
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:prompts/**/*.st");
            return java.util.Arrays.stream(resources)
                    .map(this::promptView)
                    .sorted(java.util.Comparator.comparing(PromptView::name))
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private PromptView promptView(Resource resource) {
        try {
            String path = resource.getURL().toString();
            String name = path.substring(path.indexOf("/prompts/") + 1);
            return new PromptView(name, sha256(resource.getContentAsByteArray()));
        } catch (IOException ex) {
            return new PromptView(resource.getFilename(), "unavailable");
        }
    }

    private List<Map<String, Object>> usage() {
        return jdbcTemplate.queryForList("""
                SELECT usage_date, provider_name, model_name, call_type, operation,
                       calls, successes, failures, input_tokens, output_tokens,
                       total_latency_ms, estimated_cost
                FROM ai_usage_daily
                ORDER BY usage_date DESC, calls DESC
                LIMIT 50
                """);
    }

    private List<Map<String, Object>> recentDemoJobs() {
        return jdbcTemplate.queryForList("""
                SELECT id, job_type, status, requested_by, error_category,
                       created_at, started_at, finished_at
                FROM demo_data_jobs
                ORDER BY created_at DESC
                LIMIT 20
                """);
    }

    private Map<String, Long> enterpriseExpansion() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("Data · 已批准指标", scalar(
                "SELECT COUNT(*) FROM data_metric_definitions WHERE active = TRUE"));
        counts.put("Data · 已批准查询模板", scalar(
                "SELECT COUNT(*) FROM data_query_templates WHERE active = TRUE"));
        counts.put("Knowledge · 已启用来源", scalar(
                "SELECT COUNT(*) FROM knowledge_source_connections WHERE enabled = TRUE"));
        counts.put("Knowledge · 过期或冲突资料", scalar("""
                SELECT COUNT(*) FROM knowledge_documents
                WHERE current_version = TRUE
                  AND (expires_at < now() OR conflict_status <> 'NONE')
                """));
        counts.put("Support · 已启用外部工单连接", scalar(
                "SELECT COUNT(*) FROM support_external_connections WHERE enabled = TRUE"));
        counts.put("Support · SLA 风险或违约", scalar("""
                SELECT COUNT(*) FROM support_tickets
                WHERE sla_status IN ('AT_RISK', 'BREACHED')
                """));
        counts.put("Support · 待确认回写", scalar("""
                SELECT COUNT(*) FROM support_draft_writebacks
                WHERE status = 'PENDING_CONFIRMATION'
                """));
        counts.put("Report · 已启用来源", scalar(
                "SELECT COUNT(*) FROM report_external_connections WHERE enabled = TRUE"));
        counts.put("Report · 已启用定时草稿", scalar(
                "SELECT COUNT(*) FROM report_schedules WHERE enabled = TRUE"));
        counts.put("HR · 有效候选人授权", scalar("""
                SELECT COUNT(*) FROM hr_candidate_consents
                WHERE revoked_at IS NULL AND expires_at > now()
                """));
        counts.put("HR · 已批准面试题", scalar(
                "SELECT COUNT(*) FROM hr_interview_question_bank WHERE active = TRUE"));
        counts.put("HR · 已启用 ATS 连接", scalar(
                "SELECT COUNT(*) FROM hr_ats_connections WHERE enabled = TRUE"));
        counts.put("HR · 已批准入职清单", scalar(
                "SELECT COUNT(*) FROM hr_onboarding_checklists WHERE active = TRUE"));
        return counts;
    }

    private Map<String, Object> auditSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("adminActions", scalar(
                "SELECT COUNT(*) FROM demo_admin_audit_logs"));
        summary.put("anonymizedBusinessAudits", scalar("""
                SELECT
                    (SELECT COUNT(*) FROM query_audit_logs WHERE anonymized_at IS NOT NULL)
                  + (SELECT COUNT(*) FROM knowledge_qa_audit_logs WHERE anonymized_at IS NOT NULL)
                  + (SELECT COUNT(*) FROM support_audit_logs WHERE anonymized_at IS NOT NULL)
                  + (SELECT COUNT(*) FROM report_audit_logs WHERE anonymized_at IS NOT NULL)
                  + (SELECT COUNT(*) FROM resume_audit_logs WHERE anonymized_at IS NOT NULL)
                """));
        summary.put("systemManagedKnowledge", scalar(
                "SELECT COUNT(*) FROM knowledge_documents WHERE system_managed = TRUE"));
        return summary;
    }

    private long scalar(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private boolean bool(String key, boolean fallback) {
        return environment.getProperty(key, Boolean.class, fallback);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }

    public record Diagnostics(
            Instant generatedAt,
            String runtimeMode,
            Map<String, Boolean> modules,
            long enabledScenarios,
            Map<String, Long> knowledgeIndexStates,
            Map<String, Long> knowledgeVisibility,
            ModelView models,
            List<PromptView> prompts,
            List<Map<String, Object>> usage,
            AiCallCoordinator.Diagnostics aiResilience,
            LimitView limits,
            Map<String, Long> enterpriseExpansion,
            List<Map<String, Object>> demoJobs,
            Map<String, Object> audit) {
    }

    public record ModelView(
            String provider, String chatModel, String embeddingModel, int embeddingDimension) {
    }

    public record PromptView(String name, String contentHash) {
    }

    public record LimitView(
            int clientDailyOperations, int globalDailyModelCalls, int maxConcurrentExecutions) {
    }
}
