package dev.qcoding.businesscopilot.demo;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationRequest;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationResponse;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationService;
import dev.qcoding.businesscopilot.guardrails.SchemaWhitelistValidator;
import dev.qcoding.businesscopilot.guardrails.SqlValidationContext;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerRequest;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeAnswerResponse;
import dev.qcoding.businesscopilot.knowledgecopilot.web.KnowledgeCopilotController;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportDraftResponse;
import dev.qcoding.businesscopilot.reportcopilot.generation.ReportGenerationService;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportGenerateRequest;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportType;
import dev.qcoding.businesscopilot.reportcopilot.source.ReportDataProvider;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import dev.qcoding.businesscopilot.resumecopilot.job.JobCriteriaService;
import dev.qcoding.businesscopilot.resumecopilot.job.JobDraftService;
import dev.qcoding.businesscopilot.supportcopilot.classification.TicketClassificationRequest;
import dev.qcoding.businesscopilot.supportcopilot.ticket.TicketAnalysisService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 把场景绑定到现有五模块真实业务流程，并只返回业务化安全投影。 */
@Service
public class DemoScenarioExecutionService {

    private final DemoScenarioRepository scenarioRepository;
    private final PublicDemoInputGuard inputGuard;
    private final SqlGenerationService sqlGenerationService;
    private final KnowledgeCopilotController knowledgeController;
    private final TicketAnalysisService ticketAnalysisService;
    private final JobDraftService jobDraftService;
    private final JobCriteriaService jobCriteriaService;
    private final ResumeAssessmentService resumeAssessmentService;
    private final ReportGenerationService reportGenerationService;
    private final ReportDataProvider reportDataProvider;
    private final ObjectMapper objectMapper;
    private final Semaphore executionPermits;

    public DemoScenarioExecutionService(
            DemoScenarioRepository scenarioRepository,
            PublicDemoInputGuard inputGuard,
            SqlGenerationService sqlGenerationService,
            KnowledgeCopilotController knowledgeController,
            TicketAnalysisService ticketAnalysisService,
            JobDraftService jobDraftService,
            JobCriteriaService jobCriteriaService,
            ResumeAssessmentService resumeAssessmentService,
            ReportGenerationService reportGenerationService,
            ReportDataProvider reportDataProvider,
            ObjectMapper objectMapper,
            PublicDemoProperties properties) {
        this.scenarioRepository = scenarioRepository;
        this.inputGuard = inputGuard;
        this.sqlGenerationService = sqlGenerationService;
        this.knowledgeController = knowledgeController;
        this.ticketAnalysisService = ticketAnalysisService;
        this.jobDraftService = jobDraftService;
        this.jobCriteriaService = jobCriteriaService;
        this.resumeAssessmentService = resumeAssessmentService;
        this.reportGenerationService = reportGenerationService;
        this.reportDataProvider = reportDataProvider;
        this.objectMapper = objectMapper;
        this.executionPermits = new Semaphore(properties.maxConcurrentExecutions(), true);
    }

    public ExecutionResult execute(String scenarioId, String userInput) {
        DemoScenario scenario = requireScenario(scenarioId);
        String sanitizedInput = inputGuard.validateAndSanitize(scenario.module(), userInput);
        DemoOperation operation = scenario.allowedOperations().stream().findFirst().orElseThrow(() ->
                new BusinessException(ErrorCode.DEMO_SCENARIO_NOT_AVAILABLE));
        boolean acquired = false;
        try {
            acquired = executionPermits.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(
                        ErrorCode.AI_MODEL_ERROR, "当前体验人数较多，请稍后重试。");
            }
            Object result = switch (operation) {
                case ASK_KNOWLEDGE -> executeKnowledge(scenario, sanitizedInput);
                case ANALYZE_TICKET -> executeSupport(sanitizedInput);
                case GENERATE_JOB_DRAFT -> jobDraftService.generate(sanitizedInput);
                case ASSESS_RESUME -> executeResumeAssessment(scenario, sanitizedInput, false);
                case GENERATE_INTERVIEW_QUESTIONS -> executeResumeAssessment(scenario, sanitizedInput, true);
                case GENERATE_DATA_QUERY -> executeData(scenario, sanitizedInput);
                case GENERATE_REPORT -> executeReport(scenario, sanitizedInput);
            };
            return new ExecutionResult(
                    "REALTIME", scenario.scenarioId(), scenario.version(), operation, result,
                    "本次结果由实时业务流程生成，重要事项仍需人工确认。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_MODEL_ERROR, "请求等待被中断，请稍后重试。", ex);
        } finally {
            if (acquired) executionPermits.release();
        }
    }

    public DemoScenario requireScenario(String scenarioId) {
        DemoScenario scenario = scenarioRepository.findById(scenarioId).orElseThrow(() ->
                new BusinessException(ErrorCode.DEMO_SCENARIO_NOT_AVAILABLE));
        if (!scenario.enabled() || !scenario.systemManaged()
                || scenario.allowedOperations() == null || scenario.allowedOperations().isEmpty()) {
            throw new BusinessException(ErrorCode.DEMO_SCENARIO_NOT_AVAILABLE);
        }
        return scenario;
    }

    private KnowledgeResult executeKnowledge(DemoScenario scenario, String question) {
        Map<String, Object> scope = scope(scenario);
        String category = string(scope.get("category"));
        var responseEntity = knowledgeController.askQuestion(
                new KnowledgeAnswerRequest(question, category));
        KnowledgeAnswerResponse response = responseEntity.getBody() == null
                ? null : responseEntity.getBody().data();
        if (response == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        List<String> evidence = response.citations() == null ? List.of()
                : response.citations().stream().map(citation -> citation.excerpt()).toList();
        return new KnowledgeResult(
                response.status().name(), response.answer(), evidence,
                response.warnings() == null ? List.of() : response.warnings(),
                response.status().name().equals("NO_EVIDENCE")
                        ? List.of("未找到足够制度依据，请转人工确认。") : List.of());
    }

    private SupportResult executeSupport(String customerQuestion) {
        TicketAnalysisService.TicketAnalysisResult result = ticketAnalysisService.analyze(
                new TicketClassificationRequest(customerQuestion, "public-demo"));
        var classification = result.classification();
        var draft = result.draft();
        List<EvidenceView> evidence = result.knowledgeResult() == null
                || result.knowledgeResult().evidence() == null ? List.of()
                : result.knowledgeResult().evidence().stream()
                .map(item -> new EvidenceView(
                        item.sourceTitle(), item.sectionTitle(), item.snippet(),
                        item.versionReference()))
                .toList();
        ActionHandle action = draft == null ? null
                : new ActionHandle("SUPPORT_DRAFT_REVIEW", draft.draftId(),
                draft.confirmationToken(), draft.expiresAt());
        return new SupportResult(
                classification.summary(),
                classification.category().name(),
                classification.urgency().name(),
                classification.sentiment().name(),
                draft == null ? null : draft.replyText(),
                draft == null ? null : draft.riskLevel(),
                draft == null ? List.of() : draft.riskReasons(),
                evidence,
                draft != null && draft.needsHuman(),
                draft != null && draft.needsHuman()
                        ? List.of("该问题必须由客服人员复核，系统不会自动发送或执行退款。")
                        : List.of("回复发送前仍需人工确认。"),
                action);
    }

    private Object executeResumeAssessment(
            DemoScenario scenario, String focus, boolean questionsOnly) {
        Map<String, Object> scope = scope(scenario);
        String jd = readText(string(scope.get("jobResource")));
        String resume = readText(string(scope.get("resumeResource")));
        String focusedJd = jd + "\n\n## 本次分析关注点\n" + focus;
        JobCriteriaService.CriteriaResponse criteria = jobCriteriaService.extract(
                "Java AI 应用开发工程师（虚构）", focusedJd);
        jobCriteriaService.confirm(criteria.jobId(), criteria.confirmationToken());
        ResumeAssessmentService.AssessmentResponse assessment =
                resumeAssessmentService.assess(criteria.jobId(), resume);
        if (questionsOnly) {
            return new InterviewQuestionResult(
                    assessment.content() == null ? List.of() : assessment.content().evidenceGaps(),
                    assessment.content() == null ? List.of() : assessment.content().interviewQuestions(),
                    assessment.evidence(),
                    List.of("问题用于核实材料证据，不得据此自动录用或淘汰。"),
                    new ActionHandle("RESUME_ASSESSMENT_REVIEW", assessment.assessmentId(),
                            assessment.reviewToken(), assessment.expiresAt()));
        }
        return new ResumeAssessmentView(
                assessment.status().name(),
                assessment.content(),
                assessment.evidence(),
                assessment.reviewReasons(),
                List.of("仅基于系统预置虚构简历和岗位标准，重要判断必须由面试官确认。"),
                new ActionHandle("RESUME_ASSESSMENT_REVIEW", assessment.assessmentId(),
                        assessment.reviewToken(), assessment.expiresAt()));
    }

    private DataQueryResult executeData(DemoScenario scenario, String question) {
        SqlGenerationResponse response = sqlGenerationService.generate(new SqlGenerationRequest(question));
        enforceDataScope(scenario, response);
        ActionHandle action = response.executable()
                ? new ActionHandle("DATA_QUERY_EXECUTE", response.candidateId(),
                response.confirmationToken(),
                response.expiresAt() == null ? null : response.expiresAt().toString())
                : null;
        return new DataQueryResult(
                response.sql(), response.summary(), response.assumptions(), response.warnings(),
                response.validation(), response.executable(), action,
                "实际 SQL 已展开，请确认后再执行只读查询。");
    }

    private ReportResult executeReport(DemoScenario scenario, String focus) {
        String title = scenario.title() + "：" + focus;
        if (title.length() > 280) title = title.substring(0, 280);
        ReportType type = scenario.scenarioId().contains("monthly")
                ? ReportType.BUSINESS_WEEKLY : ReportType.TEAM_WEEKLY;
        ReportGenerateRequest request = new ReportGenerateRequest(
                type,
                new ReportPeriod(LocalDate.now().minusDays(30), LocalDate.now()),
                title,
                List.of(), List.of(), List.of(),
                reportDataProvider.loadSources(),
                string(scope(scenario).get("template")), "1");
        ReportDraftResponse response = reportGenerationService.generate(request);
        return new ReportResult(
                response.status(), response.title(), response.period(), response.content(),
                response.reviewReasons(),
                List.of("报告仅保存为草稿，不会自动发布。"),
                new ActionHandle("REPORT_DRAFT_REVIEW", response.draftId(),
                        response.confirmationToken(), response.expiresAt()));
    }

    private void enforceDataScope(DemoScenario scenario, SqlGenerationResponse response) {
        Object tableObject = scope(scenario).get("tables");
        if (!(tableObject instanceof List<?> tables) || tables.isEmpty()
                || response.sql() == null || response.sql().isBlank()) {
            return;
        }
        List<String> allowed = tables.stream().map(String::valueOf)
                .map(value -> value.contains(".") ? value : "public." + value).toList();
        var violations = new java.util.ArrayList<dev.qcoding.businesscopilot.guardrails.SqlViolation>();
        new SchemaWhitelistValidator(allowed).validate(
                SqlValidationContext.forSql(response.sql(), null).build(), violations);
        if (!violations.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.DEMO_SCENARIO_NOT_AVAILABLE,
                    "该问题超出当前范例允许的数据范围，请选择更匹配的范例。");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scope(DemoScenario scenario) {
        try {
            return objectMapper.readValue(scenario.dataScopeJson(), Map.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("场景数据范围配置无效", ex);
        }
    }

    private String readText(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new BusinessException(ErrorCode.DEMO_SCENARIO_NOT_AVAILABLE);
        }
        try {
            return new ClassPathResource(resourcePath).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("场景虚构资源不可用", ex);
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record ExecutionResult(
            String source,
            String scenarioId,
            int scenarioVersion,
            DemoOperation operation,
            Object result,
            String notice) {
    }

    public record KnowledgeResult(
            String status, String conclusion, List<String> evidence,
            List<String> warnings, List<String> nextActions) {
    }

    public record EvidenceView(
            String sourceTitle, String sectionTitle, String excerpt, String version) {
    }

    public record ActionHandle(
            String actionType, Object objectReference, String confirmationToken, String expiresAt) {
    }

    public record SupportResult(
            String conclusion, String category, String urgency, String sentiment,
            String suggestedReply, String riskLevel, List<String> riskReasons,
            List<EvidenceView> evidence, boolean needsHuman, List<String> nextActions,
            ActionHandle action) {
    }

    public record ResumeAssessmentView(
            String status,
            dev.qcoding.businesscopilot.resumecopilot.ResumeModels.AssessmentContent assessment,
            List<dev.qcoding.businesscopilot.resumecopilot.ResumeModels.ResumeEvidence> evidence,
            List<String> reviewReasons,
            List<String> limitations,
            ActionHandle action) {
    }

    public record InterviewQuestionResult(
            List<String> evidenceGaps,
            List<dev.qcoding.businesscopilot.resumecopilot.ResumeModels.InterviewQuestion> questions,
            List<dev.qcoding.businesscopilot.resumecopilot.ResumeModels.ResumeEvidence> evidence,
            List<String> limitations,
            ActionHandle action) {
    }

    public record DataQueryResult(
            String sql, String summary, List<String> assumptions, List<String> warnings,
            Object validation, boolean executable, ActionHandle action, String notice) {
    }

    public record ReportResult(
            String status, String title, ReportPeriod period,
            dev.qcoding.businesscopilot.reportcopilot.generation.LlmReportOutput content,
            List<String> reviewReasons, List<String> nextActions, ActionHandle action) {
    }
}
