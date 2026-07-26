package dev.qcoding.businesscopilot.demo;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDemoBoundaryFilterTest {

    private final PublicDemoBoundaryFilter filter =
            new PublicDemoBoundaryFilter(new RuntimeModeProperties("public-demo"));

    @Test
    void blocksRawModuleApisAndCrossVisitorQueueActions() throws Exception {
        assertBlocked("POST", "/api/data-copilot/sql-candidates");
        assertBlocked("POST", "/api/knowledge-copilot/documents");
        assertBlocked("POST", "/api/resume-copilot/assessments/file");
        assertBlocked("PUT", "/api/resume-copilot/jobs/42/criteria");
        assertBlocked("GET", "/api/data-copilot/schema");
        assertBlocked("GET", "/api/report-copilot/sample-sources");
        assertBlocked("GET", "/api/support-copilot/audit-logs");
        assertBlocked("GET", "/api/support-copilot/tickets");
        assertBlocked("POST", "/api/support-copilot/reply-drafts/42/review-session");
        assertBlocked("POST", "/api/support-copilot/reply-drafts/42/mark-customer-replied");
        assertBlocked("POST", "/api/support-copilot/reply-drafts/42/edit");
        assertBlocked("POST", "/api/support-copilot/tickets/ticket-42/record-manual-reply");
        assertBlocked("GET", "/api/report-copilot/reports/42/markdown");
        assertBlocked("GET", "/api/resume-copilot/jobs/confirmed");
    }

    @Test
    void allowsScenarioReadsExecutionAndTokenProtectedActions() throws Exception {
        assertAllowed("POST", "/api/demo/scenarios/execute");
        assertAllowed("GET", "/api/demo/usage");
        assertAllowed("GET", "/api/demo/overview");
        assertAllowed("GET", "/api/demo/scenarios/example/sample-result");
        assertAllowed("POST", "/api/data-copilot/sql-candidates/candidate-42/execute");
        assertAllowed("POST", "/api/support-copilot/reply-drafts/42/confirm");
        assertAllowed("POST", "/api/report-copilot/reports/42/confirm");
        assertAllowed("POST", "/api/resume-copilot/assessments/42/review");
        assertAllowed("GET", "/api/demo/scenarios");
    }

    @Test
    void onlyAllowsAdminMaintenanceApisForAdminRole() throws Exception {
        assertBlocked("POST", "/api/admin/demo-data/initialize");
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/admin/demo-data/initialize");
        request.addUserRole("ADMIN");
        assertThat(invoke(request).getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotRestrictSelfHostedMode() throws Exception {
        PublicDemoBoundaryFilter selfHosted =
                new PublicDemoBoundaryFilter(new RuntimeModeProperties("self-hosted"));
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/support-copilot/tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        selfHosted.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void assertBlocked(String method, String path) throws Exception {
        MockHttpServletResponse response = invoke(method, path);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("BIZ_0501").doesNotContain("token");
    }

    private void assertAllowed(String method, String path) throws Exception {
        assertThat(invoke(method, path).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse invoke(String method, String path) throws Exception {
        return invoke(new MockHttpServletRequest(method, path));
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
