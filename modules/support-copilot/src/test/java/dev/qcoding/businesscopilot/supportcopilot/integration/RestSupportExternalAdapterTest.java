package dev.qcoding.businesscopilot.supportcopilot.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.qcoding.businesscopilot.commonsecurity.ExternalConnectionSecurityProperties;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RestSupportExternalAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void honorsReadAndConfirmedInternalWriteContracts(
            SupportExternalProvider provider,
            String readUri,
            String writeMethod,
            String writeUri,
            String response,
            String expectedId,
            String expectedMessage) throws Exception {
        List<CapturedRequest> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.add(capture(exchange));
                if ("GET".equals(exchange.getRequestMethod())) {
                    respondJson(exchange, 200, response);
                } else {
                    exchange.sendResponseHeaders(204, -1);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        RestSupportExternalAdapter adapter = adapter();
        SupportExternalConnection connection = new SupportExternalConnection(
                1L, "support", "Support", provider, baseUrl(),
                "SUPPORT_TOKEN", true, "admin");

        List<SupportExternalAdapter.ExternalTicket> tickets = adapter.fetchRecent(connection, 5);
        adapter.writeConfirmedDraft(connection, expectedId, "仅内部备注", "write-key-1");

        assertThat(tickets).hasSize(1);
        assertThat(tickets.getFirst().externalId()).isEqualTo(expectedId);
        assertThat(tickets.getFirst().customerMessage()).isEqualTo(expectedMessage);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).method() + " " + requests.get(0).uri())
                .isEqualTo("GET " + readUri);
        assertThat(requests.get(0).authorization()).isEqualTo("Bearer opaque-token");
        assertThat(requests.get(1).method() + " " + requests.get(1).uri())
                .isEqualTo(writeMethod + " " + writeUri);
        assertThat(requests.get(1).authorization()).isEqualTo("Bearer opaque-token");
        assertThat(requests.get(1).idempotencyKey()).isEqualTo("write-key-1");
        assertThat(requests.get(1).body()).contains("仅内部备注");
    }

    private static Stream<Arguments> providers() {
        return Stream.of(
                Arguments.of(
                        SupportExternalProvider.JIRA_SERVICE_MANAGEMENT,
                        "/rest/servicedeskapi/request?limit=5", "POST",
                        "/rest/servicedeskapi/request/jira-1/comment",
                        """
                                {"values":[{"issueId":"jira-1","description":"Jira 请求",
                                  "updated":"2026-08-15T00:00:00Z"}]}
                                """, "jira-1", "Jira 请求"),
                Arguments.of(
                        SupportExternalProvider.ZENDESK,
                        "/api/v2/tickets.json?per_page=5", "PUT",
                        "/api/v2/tickets/zendesk-1.json",
                        """
                                {"tickets":[{"id":"zendesk-1","subject":"Zendesk 请求",
                                  "updated_at":"2026-08-15T00:00:00Z"}]}
                                """, "zendesk-1", "Zendesk 请求"),
                Arguments.of(
                        SupportExternalProvider.SERVICENOW,
                        "/api/now/table/incident?sysparm_limit=5", "PATCH",
                        "/api/now/table/incident/snow-1",
                        """
                                {"result":[{"sys_id":"snow-1","short_description":"ServiceNow 请求",
                                  "sys_updated_on":"2026-08-15T00:00:00Z"}]}
                                """, "snow-1", "ServiceNow 请求"),
                Arguments.of(
                        SupportExternalProvider.FEISHU,
                        "/open-apis/helpdesk/v1/tickets?page_size=5", "POST",
                        "/open-apis/helpdesk/v1/tickets/feishu-1/comments",
                        """
                                {"data":{"items":[{"ticket_id":"feishu-1","content":"飞书请求",
                                  "update_time":"1786752000"}]}}
                                """, "feishu-1", "飞书请求"),
                Arguments.of(
                        SupportExternalProvider.WECOM,
                        "/cgi-bin/kf/tickets?limit=5", "POST",
                        "/cgi-bin/kf/tickets/wecom-1/internal-note",
                        """
                                {"items":[{"open_kfid":"wecom-1","question":"企微请求",
                                  "update_time":"1786752000"}]}
                                """, "wecom-1", "企微请求"));
    }

    private RestSupportExternalAdapter adapter() {
        ExternalConnectionSecurityProperties properties =
                new ExternalConnectionSecurityProperties(
                        List.of("localhost"), true, true,
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10),
                        1_000_000, 10, 100, 20);
        ExternalHttpClientFactory factory = new ExternalHttpClientFactory(
                new ExternalEndpointPolicy(properties));
        return new RestSupportExternalAdapter(factory, ignored -> "opaque-token");
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private CapturedRequest capture(HttpExchange exchange) throws IOException {
        return new CapturedRequest(
                exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record CapturedRequest(
            String method, String uri, String authorization, String idempotencyKey, String body) { }
}
