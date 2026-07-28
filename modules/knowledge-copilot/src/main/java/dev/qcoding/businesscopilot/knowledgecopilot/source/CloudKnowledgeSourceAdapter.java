package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SharePoint、Confluence 与 Notion 的只读 REST 适配器。 */
public class CloudKnowledgeSourceAdapter implements KnowledgeSourceAdapter {

    private final RestClient.Builder restClientBuilder;
    private final ExternalSecretResolver secretResolver;

    public CloudKnowledgeSourceAdapter(
            RestClient.Builder restClientBuilder,
            ExternalSecretResolver secretResolver) {
        this.restClientBuilder = restClientBuilder;
        this.secretResolver = secretResolver;
    }

    @Override
    public boolean supports(KnowledgeSourceProvider provider) {
        return provider == KnowledgeSourceProvider.SHAREPOINT
                || provider == KnowledgeSourceProvider.CONFLUENCE
                || provider == KnowledgeSourceProvider.NOTION;
    }

    @Override
    public SourceBatch fetch(KnowledgeSourceConnection connection, String cursor) {
        return switch (connection.provider()) {
            case SHAREPOINT -> fetchSharePoint(connection, cursor);
            case CONFLUENCE -> fetchConfluence(connection, cursor);
            case NOTION -> fetchNotion(connection, cursor);
            default -> throw new IllegalArgumentException("当前云端知识来源不受支持");
        };
    }

    private SourceBatch fetchSharePoint(KnowledgeSourceConnection connection, String cursor) {
        RestClient client = bearerClient(connection);
        String base = trimSlash(connection.baseUrl());
        String url = cursor == null || cursor.isBlank()
                ? base + "/sites/" + encode(connection.rootReference()) + "/drive/root/delta"
                : validatedCursor(base, cursor);
        JsonNode response = getJson(client, url);
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode item : iterable(response.path("value"))) {
            String id = item.path("id").asText();
            boolean deleted = !item.path("deleted").isMissingNode()
                    && !item.path("deleted").isNull();
            String driveId = item.path("parentReference").path("driveId").asText();
            byte[] content = deleted ? new byte[0] : client.get()
                    .uri(base + "/drives/" + encode(driveId) + "/items/" + encode(id) + "/content")
                    .retrieve().body(byte[].class);
            List<String> groups = deleted ? List.of()
                    : sharePointGroups(client, base, driveId, id);
            items.add(new SourceItem(id, item.path("name").asText("document.txt"),
                    item.path("file").path("mimeType").asText(null), content,
                    item.path("cTag").asText(null), item.path("eTag").asText(null),
                    instant(item.path("lastModifiedDateTime").asText(null)), groups, deleted));
        }
        String next = response.path("@odata.nextLink").asText(null);
        if (next == null) next = response.path("@odata.deltaLink").asText(null);
        return new SourceBatch(items, next, false);
    }

    private List<String> sharePointGroups(RestClient client, String base, String driveId, String itemId) {
        JsonNode permissions = getJson(client, base + "/drives/" + encode(driveId)
                + "/items/" + encode(itemId) + "/permissions");
        List<String> groups = new ArrayList<>();
        for (JsonNode permission : iterable(permissions.path("value"))) {
            addText(groups, permission.path("grantedToV2").path("group").path("displayName"));
            for (JsonNode identity : iterable(permission.path("grantedToIdentitiesV2"))) {
                addText(groups, identity.path("group").path("displayName"));
            }
        }
        return List.copyOf(groups);
    }

    private SourceBatch fetchConfluence(KnowledgeSourceConnection connection, String cursor) {
        RestClient client = bearerClient(connection);
        String base = trimSlash(connection.baseUrl());
        String url = cursor == null || cursor.isBlank()
                ? base + "/wiki/api/v2/pages?limit=100&body-format=storage&space-id="
                    + encode(connection.rootReference())
                : validatedCursor(base, cursor);
        JsonNode response = getJson(client, url);
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode page : iterable(response.path("results"))) {
            String id = page.path("id").asText();
            String html = page.path("body").path("storage").path("value").asText("");
            List<String> groups = confluenceGroups(client, base, id);
            items.add(new SourceItem(id, safeFileName(page.path("title").asText("page")) + ".html",
                    "text/html", html.getBytes(StandardCharsets.UTF_8),
                    page.path("version").path("number").asText(null),
                    page.path("version").path("message").asText(null),
                    instant(page.path("version").path("createdAt").asText(null)),
                    groups, false));
        }
        String next = response.path("_links").path("next").asText(null);
        if (next != null && next.startsWith("/")) next = base + next;
        return new SourceBatch(items, next, next == null);
    }

    private List<String> confluenceGroups(RestClient client, String base, String pageId) {
        try {
            JsonNode response = getJson(client, base + "/wiki/rest/api/content/" + encode(pageId)
                    + "/restriction/byOperation/read?expand=restrictions.group");
            List<String> groups = new ArrayList<>();
            JsonNode results = response.path("restrictions").path("group").path("results");
            for (JsonNode group : iterable(results)) {
                addText(groups, group.path("name"));
            }
            return List.copyOf(groups);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private SourceBatch fetchNotion(KnowledgeSourceConnection connection, String cursor) {
        RestClient client = notionClient(connection);
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("filter", Map.of("property", "object", "value", "page"));
        search.put("page_size", 100);
        if (cursor != null && !cursor.isBlank()) {
            search.put("start_cursor", cursor);
        }
        JsonNode response = client.post().uri(trimSlash(connection.baseUrl()) + "/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(search)
                .retrieve().body(JsonNode.class);
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode page : iterable(response.path("results"))) {
            String id = page.path("id").asText();
            String title = notionTitle(page);
            String content = notionBlocks(client, connection.baseUrl(), id);
            items.add(new SourceItem(id, safeFileName(title) + ".md", "text/markdown",
                    content.getBytes(StandardCharsets.UTF_8),
                    page.path("last_edited_time").asText(null),
                    page.path("last_edited_time").asText(null),
                    instant(page.path("last_edited_time").asText(null)),
                    List.of(), page.path("archived").asBoolean(false)
                            || page.path("in_trash").asBoolean(false)));
        }
        return new SourceBatch(items,
                response.path("has_more").asBoolean(false)
                        ? response.path("next_cursor").asText(null) : null,
                !response.path("has_more").asBoolean(false));
    }

    private String notionBlocks(RestClient client, String baseUrl, String pageId) {
        JsonNode response = getJson(client, trimSlash(baseUrl) + "/v1/blocks/"
                + encode(pageId) + "/children?page_size=100");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : iterable(response.path("results"))) {
            String type = block.path("type").asText();
            JsonNode richText = block.path(type).path("rich_text");
            for (JsonNode rich : iterable(richText)) {
                text.append(rich.path("plain_text").asText());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private String notionTitle(JsonNode page) {
        var fields = page.path("properties").properties();
        for (Map.Entry<String, JsonNode> entry : fields) {
            JsonNode title = entry.getValue().path("title");
            if (title.isArray() && !title.isEmpty()) {
                return title.get(0).path("plain_text").asText("Notion Page");
            }
        }
        return "Notion Page";
    }

    private RestClient bearerClient(KnowledgeSourceConnection connection) {
        return restClientBuilder.clone()
                .defaultHeader("Authorization", "Bearer " + secretResolver.resolve(connection.secretRef()))
                .build();
    }

    private RestClient notionClient(KnowledgeSourceConnection connection) {
        return restClientBuilder.clone()
                .defaultHeader("Authorization", "Bearer " + secretResolver.resolve(connection.secretRef()))
                .defaultHeader("Notion-Version", "2022-06-28")
                .build();
    }

    private JsonNode getJson(RestClient client, String url) {
        JsonNode body = client.get().uri(url).retrieve().body(JsonNode.class);
        if (body == null) throw new IllegalStateException("外部知识来源返回空响应");
        return body;
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private String validatedCursor(String baseUrl, String cursor) {
        if (!cursor.startsWith(baseUrl)) {
            throw new IllegalStateException("外部来源游标不属于已配置地址");
        }
        return cursor;
    }

    private void addText(List<String> values, JsonNode node) {
        String value = node.asText(null);
        if (value != null && !value.isBlank()) values.add(value);
    }

    private Instant instant(String value) {
        try {
            return value == null || value.isBlank() ? Instant.now() : Instant.parse(value);
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String safeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    }
}
