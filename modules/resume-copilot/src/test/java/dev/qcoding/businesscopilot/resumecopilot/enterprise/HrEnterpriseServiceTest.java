package dev.qcoding.businesscopilot.resumecopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.resumecopilot.assessment.ResumeAssessmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HrEnterpriseServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private HrEnterpriseService service;

    @BeforeEach
    void setUp() {
        service = new HrEnterpriseService(
                jdbcTemplate, mock(ResumeAssessmentService.class), mock(),
                mock(ExternalSecretResolver.class), new SensitiveTextMasker(),
                new ObjectMapper(), org.springframework.web.client.RestClient.builder());
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
                "consent-001", "candidate-001", "面试评估",
                grantedAt, grantedAt.minusSeconds(1));

        assertThatThrownBy(() -> service.saveConsent(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效期必须晚于授权时间");
    }

    @Test
    void emptyInterviewSummaryKeepsHumanDecisionBoundaryExplicit() {
        HrEnterpriseService.InterviewSummary summary = service.interviewSummary(77L);

        assertThat(summary.interviewerCount()).isZero();
        assertThat(summary.decisionBoundary()).contains("不形成排名、评分或录用决定");
    }
}
