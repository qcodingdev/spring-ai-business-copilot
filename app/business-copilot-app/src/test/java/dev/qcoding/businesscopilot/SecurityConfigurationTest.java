package dev.qcoding.businesscopilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HomeController.class)
@Import({
        SecurityConfiguration.class,
        SecurityConfigurationTest.ProbeController.class,
        SecurityConfigurationTest.MetricsProbeController.class,
        SecurityConfigurationTest.ConfirmationProbeController.class,
        SecurityConfigurationTest.ReviewerActionProbeController.class,
        SecurityConfigurationTest.ResumeDeleteProbeController.class,
        SecurityConfigurationTest.KnowledgeDeleteProbeController.class,
        SecurityConfigurationTest.EnterpriseProbeController.class
})
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginPageHidesFormUntilUserRequestsTheExperience() throws Exception {
        String body = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("登录体验");
        assertThat(loginFormTag(body)).contains("hidden");
    }

    @Test
    void loginFailureExpandsTheFormForImmediateCorrection() throws Exception {
        String body = mockMvc.perform(get("/login?error"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(loginFormTag(body)).doesNotContain("hidden");
        assertThat(body).contains("用户名或密码错误");
    }

    @Test
    void authenticatedBrandLinksBackToDefaultDataWorkbench() throws Exception {
        String body = mockMvc.perform(get("/")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("class=\"brand brand-home-link\"");
        assertThat(body).contains("href=\"/#data-copilot\"");
    }

    @Test
    void apiRequiresAuthenticationAndReturnsRequestId() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.errorCode").value("SEC_0401"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void reviewerCanReadAuditLogs() throws Exception {
        mockMvc.perform(get("/api/test/audit-logs")
                        .with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void operatorCannotReadAuditLogs() throws Exception {
        mockMvc.perform(get("/api/test/audit-logs")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
    }

    @Test
    void reviewerCanReadActuatorMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void operatorCannotReadActuatorMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
    }

    @Test
    void unauthenticatedUserCannotReadActuatorMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SEC_0401"));
    }

    @Test
    void operatorCanPerformBusinessActionsWithCsrf() throws Exception {
        mockMvc.perform(post("/api/test/actions")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void browserCanReturnRenderedCsrfValueInHeader() throws Exception {
        MvcResult home = mockMvc.perform(get("/")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andReturn();
        String body = home.getResponse().getContentAsString();
        java.util.regex.Matcher tokenMatcher = java.util.regex.Pattern
                .compile("<meta name=\"_csrf\" content=\"([^\"]+)\"")
                .matcher(body);
        org.assertj.core.api.Assertions.assertThat(tokenMatcher.find()).isTrue();
        String csrfToken = tokenMatcher.group(1);
        org.springframework.mock.web.MockHttpSession session =
                (org.springframework.mock.web.MockHttpSession) home.getRequest().getSession(false);

        mockMvc.perform(post("/api/test/actions")
                        .with(user("operator").roles("OPERATOR"))
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void reviewerCannotPerformOperatorActions() throws Exception {
        mockMvc.perform(post("/api/test/actions")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"))
                .andExpect(jsonPath("$.message").value("当前账号无权执行此操作"));
    }

    @Test
    void stateChangingRequestRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/test/actions")
                        .with(user("operator").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlistedApiMethodsAreDeniedByDefault() throws Exception {
        mockMvc.perform(delete("/api/test/actions")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
    }

    @Test
    void operatorCanDeleteOwnedSanitizedResumeSubmission() throws Exception {
        mockMvc.perform(delete("/api/resume-copilot/submissions/42")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deleted"));
    }

    @Test
    void reviewerCannotDeleteSanitizedResumeSubmission() throws Exception {
        mockMvc.perform(delete("/api/resume-copilot/submissions/42")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
    }

    @Test
    void reviewerCannotExecuteAConfirmedSqlCandidate() throws Exception {
        mockMvc.perform(post("/api/data-copilot/sql-candidates/candidate-1/execute")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
    }

    @Test
    void operatorCanExecuteAConfirmedSqlCandidate() throws Exception {
        mockMvc.perform(post("/api/data-copilot/sql-candidates/candidate-1/execute")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
    }

    @Test
    void reviewerCanEditAssignedSupportReviewDraft() throws Exception {
        mockMvc.perform(post("/api/support-copilot/reply-drafts/7/edit")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("edited"));
    }

    @Test
    void reviewerCanSubmitResumeAssessmentReview() throws Exception {
        mockMvc.perform(post("/api/resume-copilot/assessments/8/review")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reviewed"));
    }

    @Test
    void operatorCanDeleteOwnedKnowledgeDocument() throws Exception {
        mockMvc.perform(delete("/api/knowledge-copilot/documents/9")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deleted"));
    }

    @Test
    void reviewerCannotDeleteKnowledgeDocument() throws Exception {
        mockMvc.perform(delete("/api/knowledge-copilot/documents/9")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
    }

    @Test
    void reviewerCanSubmitKnowledgeAnswerFeedback() throws Exception {
        mockMvc.perform(post("/api/knowledge-copilot/answers/17/feedback")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("recorded"));
    }

    @Test
    void reviewerCanViewKnowledgeQualityQueue() throws Exception {
        mockMvc.perform(get("/api/knowledge-copilot/quality-queue")
                        .with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void operatorCannotViewKnowledgeQualityQueue() throws Exception {
        mockMvc.perform(get("/api/knowledge-copilot/quality-queue")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
    }

    @Test
    void reviewerCanViewKnowledgeQualityMetricsAndRecordDisposition() throws Exception {
        mockMvc.perform(get("/api/knowledge-copilot/quality-metrics")
                        .with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(post("/api/knowledge-copilot/quality-queue/17/review")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reviewed"));
    }

    @Test
    void operatorCannotViewMetricsOrRecordQualityDisposition() throws Exception {
        mockMvc.perform(get("/api/knowledge-copilot/quality-metrics")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/knowledge-copilot/quality-queue/17/review")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void enterpriseConnectionConfigurationIsAdminOnly() throws Exception {
        mockMvc.perform(post("/api/knowledge-copilot/sources")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/knowledge-copilot/sources")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void enterpriseReviewMetricsAreReviewerOnlyButReportGenerationIsOperatorAction()
            throws Exception {
        mockMvc.perform(get("/api/support-copilot/enterprise/quality-metrics")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/support-copilot/enterprise/quality-metrics")
                        .with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/report-copilot/enterprise/reports/generate")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private String loginFormTag(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<aside[^>]*id=\"login-form\"[^>]*>")
                .matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    @RestController
    @RequestMapping("/api/test")
    static class ProbeController {

        @GetMapping("/ping")
        java.util.Map<String, String> ping() {
            return java.util.Map.of("status", "ok");
        }

        @GetMapping("/audit-logs")
        java.util.Map<String, String> auditLogs() {
            return java.util.Map.of("status", "ok");
        }

        @PostMapping("/actions")
        java.util.Map<String, String> action() {
            return java.util.Map.of("status", "accepted");
        }
    }

    /** 仅用于验证安全匹配器，不替代真实 Actuator 端点的集成测试。 */
    @RestController
    static class MetricsProbeController {

        @GetMapping("/actuator/metrics")
        java.util.Map<String, String> metrics() {
            return java.util.Map.of("status", "ok");
        }
    }

    @RestController
    @RequestMapping("/api/data-copilot")
    static class ConfirmationProbeController {

        @PostMapping("/sql-candidates/{candidateId}/execute")
        java.util.Map<String, String> execute(@PathVariable("candidateId") String candidateId) {
            return java.util.Map.of("status", "confirmed", "candidateId", candidateId);
        }
    }

    @RestController
    static class ReviewerActionProbeController {

        @PostMapping("/api/support-copilot/reply-drafts/{draftId}/edit")
        java.util.Map<String, String> editSupportDraft(@PathVariable("draftId") String draftId) {
            return java.util.Map.of("status", "edited", "draftId", draftId);
        }

        @PostMapping("/api/resume-copilot/assessments/{assessmentId}/review")
        java.util.Map<String, String> reviewAssessment(@PathVariable("assessmentId") String assessmentId) {
            return java.util.Map.of("status", "reviewed", "assessmentId", assessmentId);
        }
    }

    @RestController
    @RequestMapping("/api/resume-copilot")
    static class ResumeDeleteProbeController {

        @DeleteMapping("/submissions/{submissionId}")
        java.util.Map<String, String> deleteSubmission(@PathVariable("submissionId") String submissionId) {
            return java.util.Map.of("status", "deleted", "submissionId", submissionId);
        }
    }

    @RestController
    @RequestMapping("/api/knowledge-copilot")
    static class KnowledgeDeleteProbeController {

        @DeleteMapping("/documents/{documentId}")
        java.util.Map<String, String> deleteKnowledgeDocument(@PathVariable("documentId") String documentId) {
            return java.util.Map.of("status", "deleted", "documentId", documentId);
        }

        @PostMapping("/answers/{answerId}/feedback")
        java.util.Map<String, String> recordKnowledgeFeedback(@PathVariable("answerId") String answerId) {
            return java.util.Map.of("status", "recorded", "answerId", answerId);
        }

        @GetMapping("/quality-queue")
        java.util.Map<String, String> knowledgeQualityQueue() {
            return java.util.Map.of("status", "ok");
        }

        @GetMapping("/quality-metrics")
        java.util.Map<String, String> knowledgeQualityMetrics() {
            return java.util.Map.of("status", "ok");
        }

        @PostMapping("/quality-queue/{answerId}/review")
        java.util.Map<String, String> reviewKnowledgeQuality(@PathVariable("answerId") String answerId) {
            return java.util.Map.of("status", "reviewed", "answerId", answerId);
        }
    }

    /** 仅用于验证 2.2 企业端点的角色边界，不替代各模块控制器测试。 */
    @RestController
    static class EnterpriseProbeController {

        @PostMapping("/api/knowledge-copilot/sources")
        java.util.Map<String, String> saveKnowledgeSource() {
            return java.util.Map.of("status", "saved");
        }

        @GetMapping("/api/support-copilot/enterprise/quality-metrics")
        java.util.Map<String, String> supportQualityMetrics() {
            return java.util.Map.of("status", "ok");
        }

        @PostMapping("/api/report-copilot/enterprise/reports/generate")
        java.util.Map<String, String> generateReport() {
            return java.util.Map.of("status", "generated");
        }
    }
}
