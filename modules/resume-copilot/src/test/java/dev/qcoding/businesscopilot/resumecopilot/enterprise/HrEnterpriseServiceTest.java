package dev.qcoding.businesscopilot.resumecopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HrEnterpriseServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentActorProvider actorProvider = () ->
            new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR));
    private HrEnterpriseService service;

    @BeforeEach
    void setUp() {
        service = new HrEnterpriseService(
                jdbcTemplate, mock(ResumeAssessmentService.class), actorProvider,
                mock(ExternalSecretResolver.class), new SensitiveTextMasker(),
                new ObjectMapper(),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy.class),
                mock(dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory.class));
    }

    @Test
    void rejectsInterviewQuestionThatAsksModelToRankOrRejectCandidate() {
        HrEnterpriseService.QuestionCommand command = new HrEnterpriseService.QuestionCommand(
                "java-ranking", "技术能力",
                "请根据回答给候选人打分并决定是否筛退",
                "记录候选人提供的项目证据", List.of());

        assertThatThrownBy(() -> service.saveQuestion(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能包含评分、筛退或预测性招聘决定");
        verify(jdbcTemplate, never()).queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Long>>any(),
                org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void rejectsInterviewOpinionWithoutVerifiableEvidence() {
        assertThatThrownBy(() -> service.saveOpinion(
                10L, new HrEnterpriseService.OpinionCommand(
                        List.of(), List.of("需补充项目规模"), "沟通清晰但仍需核实")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须关联可核验的证据");
    }

    @Test
    void rejectsExpiredOrReversedCandidateConsentBeforePersistence() {
        Instant grantedAt = Instant.parse("2026-07-28T10:00:00Z");
        HrEnterpriseService.ConsentCommand command = new HrEnterpriseService.ConsentCommand(
                "consent-001", "candidate-001", HrEnterpriseService.ConsentPurpose.ASSESSMENT,
                grantedAt, grantedAt.minusSeconds(1));

        assertThatThrownBy(() -> service.saveConsent(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效期必须晚于授权时间");
    }

    @Test
    void emptyInterviewSummaryKeepsHumanDecisionBoundaryExplicit() {
        org.mockito.Mockito.when(jdbcTemplate.queryForObject(
                anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        HrEnterpriseService.InterviewSummary summary = service.interviewSummary(77L);

        assertThat(summary.interviewerCount()).isZero();
        assertThat(summary.decisionBoundary()).contains("不形成排名、评分或录用决定");
    }

    @Test
    void rejectsOnboardingChecklistWithoutAnyRequiredTask() {
        HrEnterpriseService.ChecklistCommand command = new HrEnterpriseService.ChecklistCommand(
                "engineer-onboarding", "工程师入职清单", "ENGINEER",
                List.of(new HrEnterpriseService.ChecklistItem(
                        "read-handbook", "阅读员工手册", "查看最新制度", false, "EMPLOYEE")),
                List.of("knowledge:employee-handbook"));

        assertThatThrownBy(() -> service.saveChecklist(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少包含一个必办事项");
        verify(jdbcTemplate, never()).queryForObject(
                anyString(), org.mockito.ArgumentMatchers.<Class<Long>>any(),
                org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void rejectsOnboardingTaskWithoutABoundedDueDate() {
        HrEnterpriseService.ChecklistCommand command = new HrEnterpriseService.ChecklistCommand(
                "engineer-onboarding", "工程师入职清单", "ENGINEER",
                List.of(new HrEnterpriseService.ChecklistItem(
                        "read-handbook", "阅读员工手册", "查看最新制度",
                        true, "EMPLOYEE", 366)),
                List.of("knowledge:employee-handbook"));

        assertThatThrownBy(() -> service.saveChecklist(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1 到 365 天");
    }
}
