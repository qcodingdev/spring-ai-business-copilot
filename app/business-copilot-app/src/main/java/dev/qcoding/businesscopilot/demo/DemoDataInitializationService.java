package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.document.DocumentUploadService;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeVisibilityScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 从版本化 manifest 幂等导入五模块虚构数据和 15 个服务端场景。 */
@Service
public class DemoDataInitializationService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializationService.class);
    private final DemoDataJobRepository jobRepository;
    private final DemoScenarioRepository scenarioRepository;
    private final DocumentUploadService documentUploadService;
    private final PublicDemoInputGuard inputGuard;
    private final CurrentActorProvider actorProvider;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskExecutor demoDataTaskExecutor;

    public DemoDataInitializationService(
            DemoDataJobRepository jobRepository,
            DemoScenarioRepository scenarioRepository,
            DocumentUploadService documentUploadService,
            PublicDemoInputGuard inputGuard,
            CurrentActorProvider actorProvider,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TaskExecutor demoDataTaskExecutor) {
        this.jobRepository = jobRepository;
        this.scenarioRepository = scenarioRepository;
        this.documentUploadService = documentUploadService;
        this.inputGuard = inputGuard;
        this.actorProvider = actorProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.demoDataTaskExecutor = demoDataTaskExecutor;
    }

    public DemoDataJob initialize() {
        String actorId = actorProvider.currentActor().actorId();
        DemoDataJob job = jobRepository.create(DemoDataJob.JobType.INITIALIZE, actorId);
        demoDataTaskExecutor.execute(() -> executeInitialization(job.id(), actorId));
        return job;
    }

    public DemoDataJob initializeSynchronously(String actorId) {
        DemoDataJob job = jobRepository.create(DemoDataJob.JobType.INITIALIZE, actorId);
        executeInitialization(job.id(), actorId);
        return jobRepository.find(job.id()).orElse(job);
    }

    public DemoDataJob getJob(UUID id) {
        return jobRepository.find(id).orElse(null);
    }

    void executeInitialization(UUID jobId, String actorId) {
        jobRepository.running(jobId);
        try {
            SeedSummary summary = seedAll();
            jobRepository.completed(jobId, write(summary));
            audit(actorId, "INITIALIZE", summary.total(), "COMPLETED", write(summary));
        } catch (RuntimeException ex) {
            String category = safeCategory(ex);
            jobRepository.failed(jobId, category);
            audit(actorId, "INITIALIZE", 0, "FAILED", category);
            log.error("虚构数据初始化失败：jobId={}，错误类别={}", jobId, category);
        }
    }

    public SeedSummary seedAll() {
        DemoManifest manifest = readManifest();
        int documents = 0;
        int indexJobs = 0;
        for (DemoManifest.KnowledgeSeed seed : manifest.knowledgeDocuments()) {
            Resource resource = new ClassPathResource(seed.resource());
            byte[] content = readBytes(resource);
            inputGuard.validateSystemResource(seed.resource(), new String(content, StandardCharsets.UTF_8));
            DocumentUploadResponse response = documentUploadService.ingestSystemDocument(
                    resource.getFilename(), contentType(resource.getFilename()), content,
                    seed.category(), seed.logicalDocumentId(),
                    KnowledgeVisibilityScope.valueOf(seed.visibilityScope()));
            documents++;
            if (response.indexJobId() != null) indexJobs++;
        }

        int scenarios = 0;
        int sampleResults = 0;
        for (DemoManifest.ScenarioSeed seed : manifest.scenarios()) {
            String scopeJson = write(seed.dataScope());
            String contentHash = sha256(write(Map.of(
                    "scenarioId", seed.scenarioId(),
                    "module", seed.module(),
                    "title", seed.title(),
                    "description", seed.description(),
                    "inputTemplate", seed.inputTemplate(),
                    "allowedOperations", seed.allowedOperations(),
                    "dataScope", seed.dataScope(),
                    "version", seed.version())));
            scenarioRepository.upsert(new DemoScenario(
                    seed.scenarioId(), seed.module(), seed.title(), seed.description(),
                    seed.inputTemplate(), seed.allowedOperations(), scopeJson,
                    seed.dataScopeLabel(), seed.version(), true, true,
                    seed.sampleResult() != null && !seed.sampleResult().isEmpty(), contentHash));
            scenarios++;
            if (seed.sampleResult() != null && !seed.sampleResult().isEmpty()) {
                String resultJson = write(seed.sampleResult());
                scenarioRepository.upsertSampleResult(
                        seed.scenarioId(), seed.version(), resultJson,
                        manifest.generatedAt(), sha256(resultJson));
                sampleResults++;
            }
        }

        int supportRecords = seedSupport();
        int hrRecords = seedHr();
        int reportRecords = seedReports();
        markDataSystemManaged();
        return new SeedSummary(
                manifest.manifestVersion(), documents, indexJobs, scenarios,
                sampleResults, supportRecords, hrRecords, reportRecords);
    }

    private int seedSupport() {
        record SupportSeed(long id, String externalId, String message, String category,
                           String sentiment, String urgency, String status, String scenarioId) {
        }
        List<SupportSeed> seeds = List.of(
                new SupportSeed(91001, "DEMO-SUPPORT-001",
                        "订单超过退款期限，客户申请例外退货。", "REFUND", "FRUSTRATED", "HIGH",
                        "NEEDS_HUMAN", "support-refund-expired-001"),
                new SupportSeed(91002, "DEMO-SUPPORT-002",
                        "设备重启后仍然无法连接测试网络。", "INCIDENT", "CONFUSED", "HIGH",
                        "NEEDS_HUMAN", "support-device-offline-001"),
                new SupportSeed(91003, "DEMO-SUPPORT-003",
                        "咨询企业版与个人版在权限和支持方面的区别。", "PRODUCT_USAGE", "NEUTRAL", "LOW",
                        "CLASSIFIED", "support-plan-comparison-001"));
        for (SupportSeed seed : seeds) {
            jdbcTemplate.update("""
                    INSERT INTO support_tickets (
                        id, external_id, customer_message, channel, category, sentiment, urgency,
                        status, owner_actor_id, system_managed, scenario_id, created_at, updated_at
                    ) VALUES (?, ?, ?, 'public-demo', ?, ?, ?, ?, 'system-demo', TRUE, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        external_id = EXCLUDED.external_id,
                        customer_message = EXCLUDED.customer_message,
                        channel = EXCLUDED.channel,
                        category = EXCLUDED.category,
                        sentiment = EXCLUDED.sentiment,
                        urgency = EXCLUDED.urgency,
                        status = EXCLUDED.status,
                        owner_actor_id = 'system-demo',
                        system_managed = TRUE,
                        scenario_id = EXCLUDED.scenario_id,
                        updated_at = EXCLUDED.updated_at
                    """, seed.id(), seed.externalId(), seed.message(), seed.category(),
                    seed.sentiment(), seed.urgency(), seed.status(), seed.scenarioId(),
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        }
        jdbcTemplate.execute("SELECT setval('support_tickets_id_seq', GREATEST((SELECT MAX(id) FROM support_tickets), 1), true)");
        return seeds.size();
    }

    private int seedHr() {
        String criteria = """
                [
                  {"criterionId":"criterion-1","category":"SKILL","requirementType":"REQUIRED","description":"熟悉 Java 与 Spring Boot","normalizedKeywords":["java","spring boot"],"sourceText":"三年以上 Java 与 Spring Boot 项目经验"},
                  {"criterionId":"criterion-2","category":"SKILL","requirementType":"REQUIRED","description":"具有 RAG 或大模型应用经验","normalizedKeywords":["rag","大模型应用"],"sourceText":"有 RAG、向量检索或大模型应用的实际项目经验"},
                  {"criterionId":"criterion-3","category":"EXPERIENCE","requirementType":"PREFERRED","description":"有企业 AI 项目交付经验","normalizedKeywords":["企业","交付"],"sourceText":"有企业知识库、客服、HR 或数据分析项目交付经验"}
                ]
                """;
        jdbcTemplate.update("""
                INSERT INTO resume_jobs (
                    id, title, sanitized_jd, criteria_json, status, owner_actor_id,
                    logical_job_id, criteria_version, current_version, effective_from,
                    system_managed, scenario_id, created_at, updated_at
                ) VALUES (
                    92001, 'Java AI 应用开发工程师（虚构）', '系统预置虚构岗位，正文由服务端资源加载。',
                    ?, 'CRITERIA_CONFIRMED', 'system-demo',
                    'a6a8bf26-6959-4383-96a3-35c29870b201', 1, TRUE, ?,
                    TRUE, 'hr-java-ai-candidate-001', ?, ?
                )
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title,
                    sanitized_jd = EXCLUDED.sanitized_jd,
                    criteria_json = EXCLUDED.criteria_json,
                    status = 'CRITERIA_CONFIRMED',
                    owner_actor_id = 'system-demo',
                    current_version = TRUE,
                    system_managed = TRUE,
                    scenario_id = EXCLUDED.scenario_id,
                    updated_at = EXCLUDED.updated_at
                """, criteria, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        jdbcTemplate.execute("SELECT setval('resume_jobs_id_seq', GREATEST((SELECT MAX(id) FROM resume_jobs), 1), true)");
        return 1;
    }

    private int seedReports() {
        record ReportSeed(long id, String type, String title, String template, String scenario) {
        }
        List<ReportSeed> seeds = List.of(
                new ReportSeed(93001, "BUSINESS_WEEKLY", "虚构经营分析报告", "business-monthly-v1",
                        "report-monthly-business-001"),
                new ReportSeed(93002, "TEAM_WEEKLY", "虚构会议行动项", "meeting-actions-v1",
                        "report-meeting-actions-001"),
                new ReportSeed(93003, "TEAM_WEEKLY", "虚构客户问题周报", "customer-issues-weekly-v1",
                        "report-customer-issues-weekly-001"));
        for (ReportSeed seed : seeds) {
            jdbcTemplate.update("""
                    INSERT INTO report_requests (
                        id, report_type, period_start, period_end, title, owner_actor_id,
                        template_id, template_version, system_managed, scenario_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'system-demo', ?, '1', TRUE, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        report_type = EXCLUDED.report_type,
                        period_start = EXCLUDED.period_start,
                        period_end = EXCLUDED.period_end,
                        title = EXCLUDED.title,
                        owner_actor_id = 'system-demo',
                        template_id = EXCLUDED.template_id,
                        template_version = EXCLUDED.template_version,
                        system_managed = TRUE,
                        scenario_id = EXCLUDED.scenario_id
                    """, seed.id(), seed.type(), LocalDate.now().minusDays(30), LocalDate.now(),
                    seed.title(), seed.template(), seed.scenario(), Timestamp.from(Instant.now()));
        }
        jdbcTemplate.execute("SELECT setval('report_requests_id_seq', GREATEST((SELECT MAX(id) FROM report_requests), 1), true)");
        return seeds.size();
    }

    void markDataSystemManaged() {
        for (String table : List.of(
                "customers", "products", "orders", "order_items", "refunds", "marketing_events")) {
            jdbcTemplate.update("UPDATE " + table + " SET system_managed = TRUE WHERE id >= 1000");
        }
    }

    private DemoManifest readManifest() {
        try {
            return objectMapper.readValue(
                    new ClassPathResource("demo/manifest.json").getInputStream(), DemoManifest.class);
        } catch (IOException | JacksonException ex) {
            throw new IllegalStateException("无法读取虚构数据 manifest", ex);
        }
    }

    private byte[] readBytes(Resource resource) {
        try {
            return resource.getContentAsByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取虚构资源：" + resource.getFilename(), ex);
        }
    }

    private String contentType(String fileName) {
        return fileName != null && fileName.endsWith(".md") ? "text/markdown" : "text/plain";
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("虚构数据序列化失败", ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }

    private void audit(String actorId, String action, int affected, String result, String summary) {
        jdbcTemplate.update("""
                INSERT INTO demo_admin_audit_logs (
                    actor_id, action, scope_summary, affected_count, result, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, actorId, action, summary, affected, result, Timestamp.from(Instant.now()));
    }

    private String safeCategory(Throwable ex) {
        String simple = ex.getClass().getSimpleName();
        return simple.length() > 80 ? "INITIALIZATION_ERROR" : simple;
    }

    public record SeedSummary(
            int manifestVersion,
            int knowledgeDocuments,
            int indexJobsScheduled,
            int scenarios,
            int sampleResults,
            int supportRecords,
            int hrRecords,
            int reportRecords) {
        int total() {
            return knowledgeDocuments + scenarios + sampleResults + supportRecords + hrRecords + reportRecords;
        }
    }
}
