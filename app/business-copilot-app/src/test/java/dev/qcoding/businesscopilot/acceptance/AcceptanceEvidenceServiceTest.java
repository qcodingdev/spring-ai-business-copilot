package dev.qcoding.businesscopilot.acceptance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.ArrayList;
import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 分层验收证据判定测试（CORE-05）：四类分别判定，READY 不替代其他验收。 */
class AcceptanceEvidenceServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AcceptanceEvidenceService service;
    private final List<AcceptanceEvidence.Evidence> latest = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new AcceptanceEvidenceService(jdbcTemplate,
                () -> new CurrentActor("admin-1", java.util.Set.of(BusinessRole.ADMIN)), "2.4.1");
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq("2.4.1"))).thenAnswer(invocation -> List.copyOf(latest));
    }

    private void givenCategoryReturns(AcceptanceEvidence.Category category,
                                      AcceptanceEvidence.Status... statuses) {
        List<AcceptanceEvidence.Evidence> evidence = java.util.Arrays.stream(statuses)
                .map(status -> new AcceptanceEvidence.Evidence(1L, category, "check", status,
                        "test", "2.4.1", null, "tester", null))
                .toList();
        latest.removeIf(item -> item.category() == category);
        latest.addAll(evidence);
    }

    @Test
    @DisplayName("empty category is NOT_VERIFIED and blocks release")
    void emptyCategoryIsNotVerified() {
        for (AcceptanceEvidence.Category category : AcceptanceEvidence.Category.values()) {
            givenCategoryReturns(category);
        }
        AcceptanceEvidence.ReleaseReadiness readiness = service.releaseReadiness();
        assertThat(readiness.releasable()).isFalse();
        assertThat(readiness.categories())
                .allSatisfy(s -> assertThat(s.status()).isEqualTo(AcceptanceEvidence.Status.NOT_VERIFIED));
    }

    @Test
    @DisplayName("runtime readiness READY alone does not make the release releasable")
    void runtimeReadinessAloneDoesNotRelease() {
        givenCategoryReturns(AcceptanceEvidence.Category.RUNTIME_READINESS,
                AcceptanceEvidence.Status.PASS);
        givenCategoryReturns(AcceptanceEvidence.Category.MODEL_QUALITY);
        givenCategoryReturns(AcceptanceEvidence.Category.VENDOR_ACCEPTANCE);
        givenCategoryReturns(AcceptanceEvidence.Category.RELEASE_GATE);

        AcceptanceEvidence.ReleaseReadiness readiness = service.releaseReadiness();
        assertThat(readiness.releasable()).isFalse();
        assertThat(readiness.blockingReason()).contains("MODEL_QUALITY");
        assertThat(readiness.blockingReason()).contains("VENDOR_ACCEPTANCE");
        assertThat(readiness.blockingReason()).contains("RELEASE_GATE");
    }

    @Test
    @DisplayName("all four categories passing makes the release releasable")
    void allCategoriesPassingReleases() {
        for (AcceptanceEvidence.Category category : AcceptanceEvidence.Category.values()) {
            givenCategoryReturns(category, AcceptanceEvidence.Status.PASS);
        }
        assertThat(service.releaseReadiness().releasable()).isTrue();
    }

    @Test
    @DisplayName("worst status within a category wins the summary")
    void worstStatusWins() {
        givenCategoryReturns(AcceptanceEvidence.Category.MODEL_QUALITY,
                AcceptanceEvidence.Status.PASS, AcceptanceEvidence.Status.FAILED);
        List<AcceptanceEvidence.CategorySummary> summaries = service.categorySummaries();
        AcceptanceEvidence.CategorySummary modelQuality = summaries.stream()
                .filter(s -> s.category() == AcceptanceEvidence.Category.MODEL_QUALITY)
                .findFirst().orElseThrow();
        assertThat(modelQuality.status()).isEqualTo(AcceptanceEvidence.Status.FAILED);
        assertThat(modelQuality.bilingualSummary()).contains("未通过");
        assertThat(service.releaseReadiness().overall()).isEqualTo(AcceptanceEvidence.Status.FAILED);
    }

    @Test
    void httpRequestEnumsValidateAndMissingRequiredValuesReturnBadRequest() throws Exception {
        var evidenceService = mock(AcceptanceEvidenceService.class);
        when(evidenceService.record(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var controller = new AcceptanceEvidenceController(evidenceService,
                mock(dev.qcoding.businesscopilot.readiness.EnterpriseReadinessService.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new dev.qcoding.businesscopilot.commonweb.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/acceptance-evidence").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"MODEL_QUALITY","name":"test","status":"PASS",
                                 "source":"artifact:123","applicableVersion":"2.4.1"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/acceptance-evidence").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"test","source":"artifact:123","applicableVersion":""}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        org.mockito.Mockito.verify(evidenceService, org.mockito.Mockito.times(1)).record(any());
    }

    @Test
    void manualRuntimeEvidenceAndNonAdminWritesAreRejected() {
        var runtime = new AcceptanceEvidence.Evidence(null, AcceptanceEvidence.Category.RUNTIME_READINESS,
                "fake-ready", AcceptanceEvidence.Status.PASS, "manual", "2.4.1", null, "admin", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.record(runtime))
                .isInstanceOf(dev.qcoding.businesscopilot.commonweb.api.BusinessException.class);
        var operatorService = new AcceptanceEvidenceService(jdbcTemplate,
                () -> new CurrentActor("operator", java.util.Set.of(BusinessRole.OPERATOR)), "2.4.1");
        var quality = new AcceptanceEvidence.Evidence(null, AcceptanceEvidence.Category.MODEL_QUALITY,
                "quality", AcceptanceEvidence.Status.PASS, "manual", "2.4.1", null, "admin", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> operatorService.record(quality))
                .isInstanceOf(dev.qcoding.businesscopilot.commonweb.api.BusinessException.class);
    }
}
