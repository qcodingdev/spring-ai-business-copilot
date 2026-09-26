package dev.qcoding.businesscopilot.datacopilot.generation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidate;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.MetricDictionaryService;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContext;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import dev.qcoding.businesscopilot.guardrails.SqlValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlGenerationServiceTest {

    private final SchemaContextService schemaContextService = mock(SchemaContextService.class);
    private final AiChatService aiChatService = mock(AiChatService.class);
    private final PromptTemplateService promptTemplateService = new PromptTemplateService();
    private final SqlGuardrailService guardrailService = mock(SqlGuardrailService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SqlConfirmationService confirmationService = mock(SqlConfirmationService.class);
    private final MetricDictionaryService metricDictionaryService = mock(MetricDictionaryService.class);
    private final SqlCandidate candidate = mock(SqlCandidate.class);

    private final SqlGenerationService service = new SqlGenerationService(
            schemaContextService, aiChatService, promptTemplateService, guardrailService,
            auditService, new GuardrailsProperties(
                    List.of("public.orders"), null, List.of(), List.of(), 100, true, List.of()),
            confirmationService, metricDictionaryService);

    @BeforeEach
    void setUp() {
        when(schemaContextService.buildContext()).thenReturn(
                new SchemaContext(List.of(), "schema summary"));
        when(guardrailService.validate(anyString(), any(GuardrailsProperties.class)))
                .thenReturn(SqlValidationResult.pass("select 1"));
        when(aiChatService.modelName()).thenReturn("test-model");
        when(aiChatService.generatePromptJsonWithMetadata(anyString(), anyString(),
                eq(GeneratedSqlCandidate.class)))
                .thenReturn(new AiInvocationResult<>(
                        new GeneratedSqlCandidate(
                                "select sum(public.orders.total_amount) from public.orders limit 100",
                                "订单金额合计", List.of("假设：自然月"), List.of()),
                        new AiInvocationMetadata("openai-compatible", "test-model", "req-1",
                                10, 20, "stop", 25L)));
        when(candidate.candidateId()).thenReturn("cand-1");
        when(candidate.confirmationToken()).thenReturn("raw-token");
        when(candidate.ownerActorId()).thenReturn("operator-1");
        when(candidate.expiresAt()).thenReturn(Instant.now().plusSeconds(600));
        when(confirmationService.createExecutableCandidate(anyString(), anyString(), anyString(),
                any(), any(), anyString(), any())).thenReturn(candidate);
    }

    @Test
    void clarifiesAggregationQuestionWithoutTimeAnchorInsteadOfGuessing() {
        // DATA-02：聚合口径 + 无时间范围 → 先澄清，不调用模型、不生成候选。
        when(metricDictionaryService.matchingMetrics(anyString())).thenReturn(List.of());

        SqlGenerationResponse response = service.generate(
                new SqlGenerationRequest("统计一下销售额"));

        assertThat(response.executable()).isFalse();
        assertThat(response.clarificationQuestions()).isNotEmpty();
        assertThat(response.clarificationQuestions().getFirst()).contains("时间范围");
        assertThat(response.candidateId()).isNull();
        verify(aiChatService, never()).generatePromptJsonWithMetadata(anyString(), anyString(), any());
    }

    @Test
    void generatesCandidateWhenAggregationQuestionHasTimeAnchor() {
        when(metricDictionaryService.matchingMetrics(anyString())).thenReturn(List.of());
        when(metricDictionaryService.renderPromptContext(List.of()))
                .thenReturn("（当前没有已审批指标定义；只能依据 schema 白名单和问题本身确定口径，"
                        + "不得假设业务口径。）");

        SqlGenerationResponse response = service.generate(
                new SqlGenerationRequest("统计 2026 年 7 月的销售额"));

        assertThat(response.executable()).isTrue();
        assertThat(response.clarificationQuestions()).isEmpty();
        assertThat(response.adoptedMetrics()).isEmpty();
        assertThat(response.candidateId()).isEqualTo("cand-1");
    }

    @Test
    void injectsApprovedMetricCaliberAndRecordsAdoptedVersion() {
        // DATA-01：命中已审批指标 → prompt 注入口径，响应记录版本。
        MetricDictionaryService.ApprovedMetric metric = new MetricDictionaryService.ApprovedMetric(
                3L, "monthly_gmv", "月度成交额", "已支付订单金额合计", "元",
                "select sum(total_amount) from orders", 4);
        when(metricDictionaryService.matchingMetrics(anyString())).thenReturn(List.of(metric));
        when(metricDictionaryService.renderPromptContext(List.of(metric)))
                .thenReturn("- monthly_gmv（月度成交额）v4，单位：元，口径：已支付订单金额合计");

        service.generate(new SqlGenerationRequest("上个月的月度成交额是多少"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).generatePromptJsonWithMetadata(
                eq("data.sql-generation"), promptCaptor.capture(), eq(GeneratedSqlCandidate.class));
        assertThat(promptCaptor.getValue())
                .contains("monthly_gmv")
                .contains("已审批指标定义")
                .contains("月度成交额");
    }

    @Test
    void revisionInstructionIsRenderedIntoTheRegenerationPrompt() {
        when(metricDictionaryService.matchingMetrics(anyString())).thenReturn(List.of());
        when(metricDictionaryService.renderPromptContext(List.of())).thenReturn("（无）");

        service.generate(new SqlGenerationRequest(
                "统计 2026 年 7 月的销售额", "仅统计 PAID 状态并按门店分组"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).generatePromptJsonWithMetadata(
                eq("data.sql-generation"), promptCaptor.capture(), eq(GeneratedSqlCandidate.class));
        assertThat(promptCaptor.getValue()).contains("仅统计 PAID 状态并按门店分组");
    }

    @Test
    void skipsModelWhenGuardrailsRejectCandidate() {
        when(metricDictionaryService.matchingMetrics(anyString())).thenReturn(List.of());
        when(metricDictionaryService.renderPromptContext(List.of())).thenReturn("（无）");
        when(guardrailService.validate(anyString(), any(GuardrailsProperties.class)))
                .thenReturn(SqlValidationResult.fail("select 1", List.of()));

        SqlGenerationResponse response = service.generate(
                new SqlGenerationRequest("统计 2026 年 7 月的销售额"));

        assertThat(response.executable()).isFalse();
        assertThat(response.candidateId()).isNull();
        verify(confirmationService, never()).createExecutableCandidate(
                anyString(), anyString(), anyString(), any(), any(), anyString(), any());
    }
}
