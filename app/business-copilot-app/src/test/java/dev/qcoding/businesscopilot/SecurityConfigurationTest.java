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
        SecurityConfigurationTest.ConfirmationProbeController.class,
        SecurityConfigurationTest.ReviewerActionProbeController.class,
        SecurityConfigurationTest.ResumeDeleteProbeController.class,
        SecurityConfigurationTest.KnowledgeDeleteProbeController.class
})
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

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
    }
}
