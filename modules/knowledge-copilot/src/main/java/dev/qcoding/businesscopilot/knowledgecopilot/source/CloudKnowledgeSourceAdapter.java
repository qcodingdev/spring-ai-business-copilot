package dev.qcoding.businesscopilot.knowledgecopilot.source;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SharePoint、Confluence 与 Notion 的只读 REST 适配器。 */
public class CloudKnowledgeSourceAdapter implements KnowledgeSourceAdapter {

    static final String NOTION_API_VERSION = "2026-03-11";

    private final ExternalHttpClientFactory clientFactory;
    private final ExternalSecretResolver secretResolver;

    public CloudKnowledgeSourceAdapter(
            ExternalHttpClientFactory clientFactory,
            ExternalSecretResolver secretResolver) {
        this.clientFactory = clientFactory;
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
        JsonNode response = getJson(client, base + "/wiki/rest/api/content/" + encode(pageId)
                + "/restriction/byOperation/read?expand=restrictions.group");
        List<String> groups = new ArrayList<>();
        JsonNode results = response.path("restrictions").path("group").path("results");
        for (JsonNode group : iterable(results)) {
            addText(groups, group.path("name"));
        }
        return List.copyOf(groups);
    }

    private SourceBatch fetchNotion(KnowledgeSourceConnection connection, String cursor) {
        long fetchStartedNano = System.nanoTime();
        RestClient client = notionClient(connection);
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("filter", Map.of("property", "object", "value", "page"));
        search.put("page_size", 100);
        if (cursor != null && !cursor.isBlank()) {
            search.put("start_cursor", cursor);
        }
        clientFactory.ensureWithinTaskTimeout(fetchStartedNano);
        JsonNode response = clientFactory.validatePayload(client.post()
                .uri(trimSlash(connection.baseUrl()) + "/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(search)
                .retrieve().body(JsonNode.class));
        clientFactory.ensureWithinTaskTimeout(fetchStartedNano);
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode page : iterable(response.path("results"))) {
            String id = page.path("id").asText();
            String title = notionTitle(page);
            String content = notionBlocks(
                    client, connection.baseUrl(), id, fetchStartedNano);
            items.add(new SourceItem(id, safeFileName(title) + ".md", "text/markdown",
                    content.getBytes(StandardCharsets.UTF_8),
                    page.path("last_edited_time").asText(null),
                    page.path("last_edited_time").asText(null),
                    instant(page.path("last_edited_time").asText(null)),
                    List.of(), page.path("archived").asBoolean(false)
                            || page.path("in_trash").asBoolean(false)));
        }
        boolean hasMore = response.path("has_more").asBoolean(false);
        String nextCursor = response.path("next_cursor").asText(null);
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw notionFailure("Notion 搜索分页游标缺失");
        }
        return new SourceBatch(items, hasMore ? nextCursor : null, !hasMore);
    }

    private String notionBlocks(
            RestClient client, String baseUrl, String pageId, long fetchStartedNano) {
        var limits = clientFactory.properties();
        NotionTraversal traversal = new NotionTraversal(
                fetchStartedNano, limits.maxPages(), limits.maxItems(), limits.maxJsonDepth());
        StringBuilder text = new StringBuilder();
        appendNotionChildren(client, baseUrl, pageId, 1, traversal, text);
        return text.toString();
    }

    private void appendNotionChildren(
            RestClient client,
            String baseUrl,
            String parentBlockId,
            int depth,
            NotionTraversal traversal,
            StringBuilder text) {
        traversal.checkDepth(depth);
        String cursor = null;
        Set<String> seenCursors = new HashSet<>();
        while (true) {
            traversal.beforePage(clientFactory);
            String url = trimSlash(baseUrl) + "/v1/blocks/" + encode(parentBlockId)
                    + "/children?page_size=100";
            if (cursor != null) {
                url += "&start_cursor=" + encode(cursor);
            }
            JsonNode response = getJson(client, url);
            traversal.afterRequest(clientFactory);
            for (JsonNode block : iterable(response.path("results"))) {
                traversal.beforeBlock();
                appendNotionBlockText(block, text);
                if (block.path("has_children").asBoolean(false)) {
                    String childId = block.path("id").asText(null);
                    if (childId == null || childId.isBlank()) {
                        throw notionFailure("Notion 子块缺少可继续读取的 ID");
                    }
                    appendNotionChildren(client, baseUrl, childId, depth + 1, traversal, text);
                }
            }
            if (!response.path("has_more").asBoolean(false)) {
                return;
            }
            String nextCursor = response.path("next_cursor").asText(null);
            if (nextCursor == null || nextCursor.isBlank() || !seenCursors.add(nextCursor)) {
                throw notionFailure("Notion 分页游标缺失或重复");
            }
            cursor = nextCursor;
        }
    }

    private void appendNotionBlockText(JsonNode block, StringBuilder text) {
        String type = block.path("type").asText("");
        JsonNode value = block.path(type);
        String line = richText(value.path("rich_text"));
        if (line.isBlank() && ("child_page".equals(type) || "child_database".equals(type))) {
            line = value.path("title").asText("");
        }
        if ("table_row".equals(type)) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : iterable(value.path("cells"))) {
                cells.add(richText(cell));
            }
            line = String.join(" | ", cells);
        } else if ("equation".equals(type)) {
            line = value.path("expression").asText("");
        } else if ("divider".equals(type)) {
            line = "---";
        }
        if (line.isBlank()) {
            return;
        }
        String prefix = switch (type) {
            case "heading_1" -> "# ";
            case "heading_2" -> "## ";
            case "heading_3" -> "### ";
            case "bulleted_list_item" -> "- ";
            case "numbered_list_item" -> "1. ";
            case "quote" -> "> ";
            case "to_do" -> value.path("checked").asBoolean(false) ? "- [x] " : "- [ ] ";
            default -> "";
        };
        if ("code".equals(type)) {
            text.append("```\n").append(line).append("\n```\n");
        } else {
            text.append(prefix).append(line).append('\n');
        }
    }

    private String richText(JsonNode values) {
        StringBuilder text = new StringBuilder();
        for (JsonNode rich : iterable(values)) {
            text.append(rich.path("plain_text").asText(""));
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
        return clientFactory.builder(connection.baseUrl())
                .defaultHeader("Authorization", "Bearer " + secretResolver.resolve(connection.secretRef()))
                .build();
    }

    private RestClient notionClient(KnowledgeSourceConnection connection) {
        return clientFactory.builder(connection.baseUrl())
                .defaultHeader("Authorization", "Bearer " + secretResolver.resolve(connection.secretRef()))
                .defaultHeader("Notion-Version", NOTION_API_VERSION)
                .build();
    }

    private BusinessException notionFailure(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message + "。");
    }

    private JsonNode getJson(RestClient client, String url) {
        return clientFactory.validatePayload(
                client.get().uri(url).retrieve().body(JsonNode.class));
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

    private final class NotionTraversal {
        private final long startedNano;
        private final int maxPages;
        private final int maxItems;
        private final int maxDepth;
        private int pages;
        private int items;

        private NotionTraversal(long startedNano, int maxPages, int maxItems, int maxDepth) {
            this.startedNano = startedNano;
            this.maxPages = maxPages;
            this.maxItems = maxItems;
            this.maxDepth = maxDepth;
        }

        private void beforePage(ExternalHttpClientFactory factory) {
            factory.ensureWithinTaskTimeout(startedNano);
            pages++;
            if (pages > maxPages) {
                throw notionFailure("Notion 块分页超过安全限制");
            }
        }

        private void beforeBlock() {
            items++;
            if (items > maxItems) {
                throw notionFailure("Notion 块条目超过安全限制");
            }
        }

        private void afterRequest(ExternalHttpClientFactory factory) {
            factory.ensureWithinTaskTimeout(startedNano);
        }

        private void checkDepth(int depth) {
            if (depth > maxDepth) {
                throw notionFailure("Notion 块层级超过安全限制");
            }
        }
    }
}
