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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
    void revokingConsentImmediatelyExpiresAndCancelsExistingDerivedWork() {
        Instant now = Instant.now();
        org.mockito.Mockito.when(jdbcTemplate.query(
                        contains("UPDATE hr_candidate_consents"),
                        org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<HrEnterpriseService.Consent>>any(),
                        eq("consent-41"), eq("operator-1"), eq(false)))
                .thenReturn(List.of(new HrEnterpriseService.Consent(
                        41L, "consent-41", "candidate-41",
                        HrEnterpriseService.ConsentPurpose.ASSESSMENT,
                        now.minusSeconds(60), now.plusSeconds(3600), now, "operator-1")));

        service.revokeConsent("consent-41");

        verify(jdbcTemplate).update(contains("UPDATE resume_submissions"), eq(41L));
        verify(jdbcTemplate).update(contains("UPDATE hr_interview_sessions"), eq(41L));
        verify(jdbcTemplate).update(contains("UPDATE hr_ats_imports"), eq("consent-41"));
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
    void conflictingInterviewOpinionsRemainAttributedAndGapsStaySeparate() {
        org.mockito.Mockito.when(jdbcTemplate.queryForObject(
                anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        Instant now = Instant.now();
        List<HrEnterpriseService.InterviewOpinion> opinions = List.of(
                new HrEnterpriseService.InterviewOpinion(
                        1L, 77L, "interviewer-a", List.of("证据 A"),
                        List.of("需核实并发规模"), "认为经验与岗位要求相符", now),
                new HrEnterpriseService.InterviewOpinion(
                        2L, 77L, "interviewer-b", List.of("证据 B"),
                        List.of("需核实项目角色"), "认为现有证据仍不足", now.plusSeconds(1)));
        org.mockito.Mockito.doReturn(opinions).when(jdbcTemplate).query(
                contains("FROM hr_interview_opinions"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<HrEnterpriseService.InterviewOpinion>>any(),
                eq(77L));

        HrEnterpriseService.InterviewSummary summary = service.interviewSummary(77L);

        assertThat(summary.opinions()).extracting(HrEnterpriseService.InterviewOpinion::interviewerActorId)
                .containsExactly("interviewer-a", "interviewer-b");
        assertThat(summary.evidenceGaps())
                .containsExactly("需核实并发规模", "需核实项目角色");
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
