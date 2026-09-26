package dev.qcoding.businesscopilot.reportcopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.ExternalConnectionSecurityProperties;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.reportcopilot.request.ReportPeriod;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraReportSourceClientTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ExternalHttpClientFactory clients = mock(ExternalHttpClientFactory.class);
    private final ReportEnterpriseService.Connection connection = new ReportEnterpriseService.Connection(
            1, "jira-fixture", "Fixture", ReportEnterpriseService.Provider.JIRA, "https://jira.example.test",
            "FIXTURE", true, "operator", List.of("DEMO"));
    private final ReportPeriod period = new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), "Asia/Shanghai");

    private JiraReportSourceClient client(int maxPages) {
        when(clients.builder(anyString())).thenReturn(builder);
        when(clients.validatePayload(any())).thenAnswer(inv -> inv.getArgument(0));
        when(clients.properties()).thenReturn(new ExternalConnectionSecurityProperties(
                List.of(), false, false, null, null, null, 10000, maxPages, 10, 16));
        return new JiraReportSourceClient(clients);
    }

    @Test
    void collectsEveryPageWithProjectScopeAndExactInstants() {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/rest/api/3/search/jql");
            String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
            assertThat(query).contains("project in (\"DEMO\")", "updated >=", "updated <", "fields=summary,status,updated,project");
        }).andRespond(withSuccess("""
                {"isLast":false,"nextPageToken":"next+&page","issues":[
                  {"key":"DEMO-1","fields":{"summary":"In period","project":{"key":"DEMO"},"status":{"name":"Done"},"updated":"2026-06-30T16:00:00.000+0000"}},
                  {"key":"DEMO-2","fields":{"summary":"After period","project":{"key":"DEMO"},"status":{"name":"Open"},"updated":"2026-07-07T16:00:00.000+0000"}}
                ]}
                """, MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8))
                .contains("nextPageToken=next+&page")).andRespond(withSuccess("""
                {"isLast":true,"issues":[
                  {"key":"DEMO-3","fields":{"summary":"Last day","project":{"key":"DEMO"},"status":{"name":"Done"},"updated":"2026-07-07T15:59:59Z"}},
                  {"key":"DEMO-4","fields":{"summary":"Before period","project":{"key":"DEMO"},"status":{"name":"Open"},"updated":"2026-06-30T15:59:59Z"}}
                ]}
                """, MediaType.APPLICATION_JSON));
        var sources = client(2).collect(connection, period, "Bearer fixture");
        assertThat(sources).hasSize(2);
        assertThat(sources.getFirst().observedAt()).isEqualTo(Instant.parse("2026-06-30T16:00:00Z"));
        assertThat(sources.getFirst().attributes()).containsEntry("completeness", "ALL_SEARCH_PAGES");
        assertThat(sources).extracting(source -> source.attributes().get("issueKey")).containsExactly("DEMO-1", "DEMO-3");
        server.verify();
    }

    @Test
    void exceedingPageBudgetCannotProduceAnApparentlyCompleteReport() {
        server.expect(request -> { }).andRespond(withSuccess("{\"isLast\":false,\"nextPageToken\":\"next\",\"issues\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client(1).collect(connection, period, "Bearer fixture"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("分页上限").hasMessageContaining("报告未生成");
        server.verify();
    }

    @Test
    void missingEndMarkerIsAnIncompleteResponse() {
        server.expect(request -> { }).andRespond(withSuccess("{\"issues\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client(2).collect(connection, period, "Bearer fixture"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("分页未完整结束");
    }

    @Test
    void rejectsUnscopedAndInjectedProjectKeysBeforeDispatch() {
        assertThatThrownBy(() -> JiraReportSourceClient.projectKeys(List.of("DEMO) OR 1=1")))
                .isInstanceOf(BusinessException.class);
        var unscoped = new ReportEnterpriseService.Connection(2, "jira", "Fixture", ReportEnterpriseService.Provider.JIRA,
                "https://jira.example.test", "FIXTURE", true, "operator", List.of());
        assertThatThrownBy(() -> client(2).collect(unscoped, period, "Bearer fixture"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("项目范围");
    }
}
