package dev.qcoding.businesscopilot.commonsecurity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalHttpClientFactoryTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rejectsDeclaredAndStreamedResponsesBeyondByteBudget() throws Exception {
        startServer(exchange -> {
            byte[] bytes = "response-too-large".getBytes(StandardCharsets.UTF_8);
            if (exchange.getRequestURI().getPath().endsWith("streamed")) {
                exchange.sendResponseHeaders(200, 0);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
            }
            exchange.getResponseBody().write(bytes);
        });
        RestClient client = factory(8).builder(baseUrl()).build();

        assertThatThrownBy(() -> client.get().uri(baseUrl() + "/declared")
                .retrieve().body(byte[].class))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("响应超过安全大小限制");
        assertThatThrownBy(() -> client.get().uri(baseUrl() + "/streamed")
                .retrieve().body(byte[].class))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("响应超过安全大小限制");
    }

    @Test
    void rejectsRedirectWithoutFollowingLocation() throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/target")) {
                targetRequests.incrementAndGet();
                respond(exchange, 200, "unexpected");
            } else {
                exchange.getResponseHeaders().set("Location", baseUrl() + "/target");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        RestClient client = factory(1024).builder(baseUrl()).build();

        assertThatThrownBy(() -> client.get().uri(baseUrl() + "/redirect")
                .retrieve().body(String.class))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重定向需要重新配置");
        assertThat(targetRequests).hasValue(0);
    }

    private ExternalHttpClientFactory factory(long maxBytes) {
        ExternalConnectionSecurityProperties properties =
                new ExternalConnectionSecurityProperties(
                        List.of("localhost"), true, true,
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10),
                        maxBytes, 5, 20, 10);
        return new ExternalHttpClientFactory(new ExternalEndpointPolicy(properties));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
