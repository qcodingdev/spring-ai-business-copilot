package dev.qcoding.businesscopilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HomeController.class)
@Import({
        SecurityConfiguration.class,
        SecurityConfigurationTest.ProbeController.class,
        SecurityConfigurationTest.ConfirmationProbeController.class
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
    void reviewerCannotPerformOperatorActions() throws Exception {
        mockMvc.perform(post("/api/test/actions")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC_0403"));
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
    void reviewerCanExecuteAConfirmedSqlCandidate() throws Exception {
        mockMvc.perform(post("/api/data-copilot/sql-candidates/candidate-1/execute")
                        .with(user("reviewer").roles("REVIEWER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
    }

    @Test
    void operatorCanExecuteAConfirmedSqlCandidate() throws Exception {
        mockMvc.perform(post("/api/data-copilot/sql-candidates/candidate-1/execute")
                        .with(user("operator").roles("OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
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
}
