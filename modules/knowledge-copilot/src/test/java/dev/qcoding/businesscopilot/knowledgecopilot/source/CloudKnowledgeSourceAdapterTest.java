package dev.qcoding.businesscopilot.knowledgecopilot.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.qcoding.businesscopilot.commonsecurity.ExternalConnectionSecurityProperties;
import dev.qcoding.businesscopilot.commonsecurity.ExternalEndpointPolicy;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeVisibilityScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudKnowledgeSourceAdapterTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsEveryNotionBlockPageAndNestedChildWithCurrentApiVersion() throws Exception {
        startServer(exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-secret");
            assertThat(exchange.getRequestHeaders().getFirst("Notion-Version"))
                    .isEqualTo(CloudKnowledgeSourceAdapter.NOTION_API_VERSION);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if ("/v1/search".equals(path)) {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                respond(exchange, 200, searchResponse());
            } else if ("/v1/blocks/page-1/children".equals(path)
                    && query != null && query.contains("start_cursor=page-2")) {
                respond(exchange, 200, """
                        {"results":[
                          {"id":"last","type":"paragraph","has_children":false,
                           "paragraph":{"rich_text":[{"plain_text":"第二页内容"}]}}
                        ],"has_more":false,"next_cursor":null}
                        """);
            } else if ("/v1/blocks/page-1/children".equals(path)) {
                respond(exchange, 200, """
                        {"results":[
                          {"id":"heading","type":"heading_1","has_children":false,
                           "heading_1":{"rich_text":[{"plain_text":"完整页面"}]}},
                          {"id":"toggle-1","type":"toggle","has_children":true,
                           "toggle":{"rich_text":[{"plain_text":"展开项"}]}},
                          {"id":"row","type":"table_row","has_children":false,
                           "table_row":{"cells":[
                             [{"plain_text":"列一"}],[{"plain_text":"列二"}]
                           ]}}
                        ],"has_more":true,"next_cursor":"page-2"}
                        """);
            } else if ("/v1/blocks/toggle-1/children".equals(path)) {
                respond(exchange, 200, """
                        {"results":[
                          {"id":"nested","type":"paragraph","has_children":false,
                           "paragraph":{"rich_text":[{"plain_text":"嵌套内容"}]}}
                        ],"has_more":false,"next_cursor":null}
                        """);
            } else {
                respond(exchange, 404, "{}");
            }
        });

        KnowledgeSourceAdapter.SourceBatch batch = adapter(10).fetch(connection(), null);

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.fullSnapshot()).isTrue();
        assertThat(batch.nextCursor()).isNull();
        assertThat(batch.items().getFirst().fileName()).isEqualTo("企业知识.md");
        assertThat(new String(batch.items().getFirst().content(), StandardCharsets.UTF_8))
                .isEqualTo("# 完整页面\n展开项\n嵌套内容\n列一 | 列二\n第二页内容\n");
        assertThat(requests).containsExactly(
                "POST /v1/search",
                "GET /v1/blocks/page-1/children?page_size=100",
                "GET /v1/blocks/toggle-1/children?page_size=100",
                "GET /v1/blocks/page-1/children?page_size=100&start_cursor=page-2");
    }

    @Test
    void failsClosedInsteadOfSilentlyTruncatingWhenBlockPageBudgetIsExceeded() throws Exception {
        startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/v1/search".equals(path)) {
                respond(exchange, 200, searchResponse());
            } else {
                respond(exchange, 200, """
                        {"results":[
                          {"id":"first","type":"paragraph","has_children":false,
                           "paragraph":{"rich_text":[{"plain_text":"第一页"}]}}
                        ],"has_more":true,"next_cursor":"next"}
                        """);
            }
        });

        assertThatThrownBy(() -> adapter(1).fetch(connection(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分页超过安全限制");
    }

    @Test
    void failsClosedWhenNotionSearchSignalsAnotherPageWithoutCursor() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
                {"results":[],"has_more":true,"next_cursor":null}
                """));

        assertThatThrownBy(() -> adapter(10).fetch(connection(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("搜索分页游标缺失");
    }

    @Test
    void mapsSharePointDeltaContentAndAclContract() throws Exception {
        startServer(exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-secret");
            String path = exchange.getRequestURI().getPath();
            if ("/sites/site-1/drive/root/delta".equals(path)) {
                respond(exchange, 200, """
                        {"value":[{
                          "id":"item-1","name":"guide.txt","file":{"mimeType":"text/plain"},
                          "parentReference":{"driveId":"drive-1"},"cTag":"c1","eTag":"e1",
                          "lastModifiedDateTime":"2026-08-15T00:00:00Z"
                        }],"@odata.nextLink":"%s/next"}
                        """.formatted(baseUrl()));
            } else if ("/drives/drive-1/items/item-1/content".equals(path)) {
                respond(exchange, 200, "SharePoint 正文");
            } else if ("/drives/drive-1/items/item-1/permissions".equals(path)) {
                respond(exchange, 200, """
                        {"value":[
                          {"grantedToV2":{"group":{"displayName":"知识组"}}},
                          {"grantedToIdentitiesV2":[{"group":{"displayName":"审核组"}}]}
                        ]}
                        """);
            } else {
                respond(exchange, 404, "{}");
            }
        });

        KnowledgeSourceAdapter.SourceBatch batch = adapter(10).fetch(
                connection(KnowledgeSourceProvider.SHAREPOINT, "site-1"), null);

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.items().getFirst().sourceItemId()).isEqualTo("item-1");
        assertThat(new String(batch.items().getFirst().content(), StandardCharsets.UTF_8))
                .isEqualTo("SharePoint 正文");
        assertThat(batch.items().getFirst().allowedGroups()).containsExactly("知识组", "审核组");
        assertThat(batch.nextCursor()).isEqualTo(baseUrl() + "/next");
        assertThat(batch.fullSnapshot()).isFalse();
    }

    @Test
    void mapsConfluencePageBodyAclAndRelativeCursorContract() throws Exception {
        startServer(exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-secret");
            String path = exchange.getRequestURI().getPath();
            if ("/wiki/api/v2/pages".equals(path)) {
                respond(exchange, 200, """
                        {"results":[{
                          "id":"page-7","title":"操作手册",
                          "body":{"storage":{"value":"<p>Confluence 正文</p>"}},
                          "version":{"number":7,"message":"published",
                                     "createdAt":"2026-08-15T00:00:00Z"}
                        }],"_links":{"next":"/wiki/api/v2/pages?cursor=next"}}
                        """);
            } else if ("/wiki/rest/api/content/page-7/restriction/byOperation/read".equals(path)) {
                respond(exchange, 200, """
                        {"restrictions":{"group":{"results":[{"name":"工程组"}]}}}
                        """);
            } else {
                respond(exchange, 404, "{}");
            }
        });

        KnowledgeSourceAdapter.SourceBatch batch = adapter(10).fetch(
                connection(KnowledgeSourceProvider.CONFLUENCE, "space-1"), null);

        assertThat(batch.items()).hasSize(1);
        assertThat(batch.items().getFirst().fileName()).isEqualTo("操作手册.html");
        assertThat(new String(batch.items().getFirst().content(), StandardCharsets.UTF_8))
                .isEqualTo("<p>Confluence 正文</p>");
        assertThat(batch.items().getFirst().allowedGroups()).containsExactly("工程组");
        assertThat(batch.nextCursor()).isEqualTo(baseUrl() + "/wiki/api/v2/pages?cursor=next");
        assertThat(batch.fullSnapshot()).isFalse();
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

    private CloudKnowledgeSourceAdapter adapter(int maxPages) {
        ExternalConnectionSecurityProperties properties =
                new ExternalConnectionSecurityProperties(
                        List.of("localhost"), true, true,
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10),
                        1_000_000, maxPages, 100, 10);
        ExternalHttpClientFactory factory = new ExternalHttpClientFactory(
                new ExternalEndpointPolicy(properties));
        return new CloudKnowledgeSourceAdapter(factory, ignored -> "test-secret");
    }

    private KnowledgeSourceConnection connection() {
        return connection(KnowledgeSourceProvider.NOTION, null);
    }

    private KnowledgeSourceConnection connection(KnowledgeSourceProvider provider, String rootReference) {
        return new KnowledgeSourceConnection(
                1L, provider.name().toLowerCase(), provider.name(), provider,
                baseUrl(), rootReference,
                "NOTION_TOKEN", Map.of(), KnowledgeVisibilityScope.ALL, true, "admin");
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private String searchResponse() {
        return """
                {"results":[{
                  "id":"page-1","archived":false,"in_trash":false,
                  "last_edited_time":"2026-08-15T00:00:00Z",
                  "properties":{"Name":{"title":[{"plain_text":"企业知识"}]}}
                }],"has_more":false,"next_cursor":null}
                """;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
