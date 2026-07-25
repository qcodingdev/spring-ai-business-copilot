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
    void blocksDirectAiRequestAndUploads() throws Exception {
        assertBlocked("POST", "/api/data-copilot/sql-candidates");
        assertBlocked("POST", "/api/knowledge-copilot/documents");
        assertBlocked("POST", "/api/resume-copilot/assessments/file");
        assertBlocked("PUT", "/api/resume-copilot/jobs/42/criteria");
        assertBlocked("GET", "/api/data-copilot/schema");
        assertBlocked("GET", "/api/report-copilot/sample-sources");
        assertBlocked("GET", "/api/support-copilot/audit-logs");
    }

    @Test
    void allowsScenarioExecutionAndHumanConfirmation() throws Exception {
        assertAllowed("POST", "/api/demo/scenarios/execute");
        assertAllowed("POST", "/api/support-copilot/reply-drafts/42/confirm");
        assertAllowed("GET", "/api/demo/scenarios");
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
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
