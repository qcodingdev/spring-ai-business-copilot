package dev.qcoding.businesscopilot.supportcopilot.integration;

import dev.qcoding.businesscopilot.commonsecurity.ExternalSecretResolver;
import dev.qcoding.businesscopilot.commonsecurity.ExternalHttpClientFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Jira Service Management、Zendesk、ServiceNow、飞书和企微的 REST 适配器。 */
public class RestSupportExternalAdapter implements SupportExternalAdapter {

    private final ExternalHttpClientFactory clientFactory;
    private final ExternalSecretResolver secretResolver;

    public RestSupportExternalAdapter(ExternalHttpClientFactory clientFactory,
                                      ExternalSecretResolver secretResolver) {
        this.clientFactory = clientFactory;
        this.secretResolver = secretResolver;
    }

    @Override
    public boolean supports(SupportExternalProvider provider) {
        return true;
    }

    @Override
    public List<ExternalTicket> fetchRecent(SupportExternalConnection connection, int limit) {
        RestClient client = client(connection);
        String base = trimSlash(connection.baseUrl());
        String url = switch (connection.provider()) {
            case JIRA_SERVICE_MANAGEMENT -> base + "/rest/servicedeskapi/request?limit=" + limit;
            case ZENDESK -> base + "/api/v2/tickets.json?per_page=" + limit;
            case SERVICENOW -> base + "/api/now/table/incident?sysparm_limit=" + limit;
            case FEISHU -> base + "/open-apis/helpdesk/v1/tickets?page_size=" + limit;
            case WECOM -> base + "/cgi-bin/kf/tickets?limit=" + limit;
        };
        JsonNode response = clientFactory.validatePayload(
                client.get().uri(url).retrieve().body(JsonNode.class));
        JsonNode items = firstArray(response, "values", "tickets", "result", "items");
        List<ExternalTicket> tickets = new ArrayList<>();
        for (JsonNode item : iterable(items)) {
            String id = firstText(item, "id", "issueId", "sys_id", "ticket_id", "open_kfid");
            String message = firstText(item, "description", "subject", "short_description",
                    "summary", "content", "question");
            if (id == null || message == null) continue;
            Instant updated = parseInstant(firstText(item,
                    "updated_at", "updated", "sys_updated_on", "update_time"));
            Instant due = parseInstant(firstText(item,
                    "due_at", "resolution_due", "sla_due_at", "due_time"));
            tickets.add(new ExternalTicket(id, message, connection.provider().name(),
                    updated, due, objectMap(item.path("customer")),
                    objectMap(item.path("order")), objectMap(item.path("service"))));
            if (tickets.size() >= limit) break;
        }
        return List.copyOf(tickets);
    }

    @Override
    public void writeConfirmedDraft(
            SupportExternalConnection connection,
            String externalTicketId,
            String sanitizedDraft) {
        RestClient client = client(connection);
        String base = trimSlash(connection.baseUrl());
        switch (connection.provider()) {
            case JIRA_SERVICE_MANAGEMENT -> client.post()
                    .uri(base + "/rest/servicedeskapi/request/" + externalTicketId + "/comment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("body", sanitizedDraft, "public", false))
                    .retrieve().toBodilessEntity();
            case ZENDESK -> client.put()
                    .uri(base + "/api/v2/tickets/" + externalTicketId + ".json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ticket", Map.of(
                            "comment", Map.of("body", sanitizedDraft, "public", false))))
                    .retrieve().toBodilessEntity();
            case SERVICENOW -> client.patch()
                    .uri(base + "/api/now/table/incident/" + externalTicketId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("work_notes", sanitizedDraft))
                    .retrieve().toBodilessEntity();
            case FEISHU -> client.post()
                    .uri(base + "/open-apis/helpdesk/v1/tickets/" + externalTicketId + "/comments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", sanitizedDraft, "visibility", "internal"))
                    .retrieve().toBodilessEntity();
            case WECOM -> client.post()
                    .uri(base + "/cgi-bin/kf/tickets/" + externalTicketId + "/internal-note")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", sanitizedDraft))
                    .retrieve().toBodilessEntity();
        }
    }

    private RestClient client(SupportExternalConnection connection) {
        String secret = secretResolver.resolve(connection.secretRef());
        String authorization = secret.contains(" ") ? secret : "Bearer " + secret;
        return clientFactory.builder(connection.baseUrl())
                .defaultHeader("Authorization", authorization).build();
    }

    private JsonNode firstArray(JsonNode root, String... names) {
        if (root == null) return null;
        for (String name : names) {
            JsonNode candidate = root.path(name);
            if (candidate.isArray()) return candidate;
            JsonNode nested = root.path("data").path(name);
            if (nested.isArray()) return nested;
        }
        return root.isArray() ? root : null;
    }

    private Iterable<JsonNode> iterable(JsonNode value) {
        return value != null && value.isArray() ? value : List.of();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isValueNode() && !value.asText().isBlank()) return value.asText();
            JsonNode fields = node.path("fields").path(name);
            if (fields.isValueNode() && !fields.asText().isBlank()) return fields.asText();
        }
        return null;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return Instant.now();
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(value));
            } catch (RuntimeException ignored) {
                return Instant.now();
            }
        }
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (entry.getValue().isValueNode()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return Map.copyOf(values);
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
