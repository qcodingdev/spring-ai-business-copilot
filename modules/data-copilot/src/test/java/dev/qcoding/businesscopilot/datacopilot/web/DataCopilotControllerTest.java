package dev.qcoding.businesscopilot.datacopilot.web;

import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.audit.QueryAuditLog;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlCandidateValidationSummary;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationRequest;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationResponse;
import dev.qcoding.businesscopilot.datacopilot.generation.SqlGenerationService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryColumn;
import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;
import dev.qcoding.businesscopilot.datacopilot.query.QueryRow;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationResponse;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContext;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataCopilotControllerTest {

    private SchemaContextService schemaContextService;
    private SqlGenerationService sqlGenerationService;
    private QueryExecutionService queryExecutionService;
    private AuditService auditService;
    private DataCopilotController controller;

    @BeforeEach
    void setUp() {
        schemaContextService = mock(SchemaContextService.class);
        sqlGenerationService = mock(SqlGenerationService.class);
        queryExecutionService = mock(QueryExecutionService.class);
        auditService = mock(AuditService.class);
        controller = new DataCopilotController(
                schemaContextService, sqlGenerationService,
                queryExecutionService, auditService);
    }

    // ---- /schema ----

    @Test
    @DisplayName("GET /schema returns whitelisted schema context")
    void getSchemaReturnsContext() {
        SchemaContext context = new SchemaContext(List.of(), "Table: customers");
        when(schemaContextService.buildContext()).thenReturn(context);

        var response = controller.getSchema();

        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().textSummary()).contains("customers");
    }

    // ---- /sql-candidates ----

    @Test
    @DisplayName("POST /sql-candidates returns generation response")
    void createSqlCandidateReturnsResponse() {
        SqlGenerationRequest request = new SqlGenerationRequest("上个月销售额");
        SqlGenerationResponse genResponse = new SqlGenerationResponse(
                "req-001", "上个月销售额",
                "SELECT SUM(amount) FROM orders LIMIT 100",
                "Total sales last month",
                List.of(), List.of(),
                new SqlCandidateValidationSummary(true, List.of()),
                true, "cand-1", "token-1", Instant.now().plusSeconds(600));
        when(sqlGenerationService.generate(any(SqlGenerationRequest.class))).thenReturn(genResponse);

        var response = controller.createSqlCandidate(request);

        assertThat(response.getBody().success()).isTrue();
        SqlGenerationResponse data = response.getBody().data();
        assertThat(data.executable()).isTrue();
        assertThat(data.candidateId()).isEqualTo("cand-1");
        assertThat(data.confirmationToken()).isEqualTo("token-1");
    }

    @Test
    @DisplayName("guardrails failure returns executable=false with no token")
    void guardrailsFailureReturnsNotExecutable() {
        SqlGenerationRequest request = new SqlGenerationRequest("delete all");
        SqlGenerationResponse genResponse = SqlGenerationResponse.notExecutable(
                "req-002", "delete all", "DELETE FROM customers",
                "Delete all customers", List.of(), List.of(),
                new SqlCandidateValidationSummary(false, List.of("FORBIDDEN_KEYWORD: delete")));
        when(sqlGenerationService.generate(any(SqlGenerationRequest.class))).thenReturn(genResponse);

        var response = controller.createSqlCandidate(request);

        SqlGenerationResponse data = response.getBody().data();
        assertThat(data.executable()).isFalse();
        assertThat(data.candidateId()).isNull();
        assertThat(data.confirmationToken()).isNull();
    }

    // ---- /sql-candidates/{candidateId}/execute ----

    @Test
    @DisplayName("execute success returns table and explanation")
    void executeSuccessReturnsTableAndExplanation() {
        String candidateId = "cand-1";
        String token = "token-1";
        SqlExecutionRequest execRequest = new SqlExecutionRequest(token);

        QueryResultTable table = new QueryResultTable(
                List.of(new QueryColumn("id", "integer"), new QueryColumn("name", "varchar")),
                List.of(new QueryRow(Map.of("id", 1, "name", "Alice"))),
                1, false);
        ResultExplanationResponse explanation = ResultExplanationResponse.success(
                "Found 1 customer named Alice.");
        SqlExecutionResponse execResponse = new SqlExecutionResponse(table, explanation);

        when(queryExecutionService.execute(candidateId, token)).thenReturn(execResponse);

        var response = controller.executeSqlCandidate(candidateId, execRequest);

        assertThat(response.getBody().success()).isTrue();
        SqlExecutionResponse data = response.getBody().data();
        assertThat(data.table().rows()).hasSize(1);
        assertThat(data.explanation().explanation()).contains("Alice");
        assertThat(data.explanation().degraded()).isFalse();
    }

    // ---- /audit-logs ----

    @Test
    @DisplayName("GET /audit-logs returns paginated logs")
    void getAuditLogsReturnsPaginated() {
        QueryAuditLog log = new QueryAuditLog(
                1L, "req-001", "http-req-001", "operator", "question", "sql", "sql",
                "EXECUTED", null, true, "QUERY_SUCCESS", 5,
                null, "gpt-5-mini", 100L, Instant.now());
        when(auditService.findRecent(0, 20)).thenReturn(List.of(log));

        var response = controller.getAuditLogs(0, 20);

        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).hasSize(1);
    }
}
