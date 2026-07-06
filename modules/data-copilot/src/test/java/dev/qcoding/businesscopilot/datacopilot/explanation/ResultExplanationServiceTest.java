package dev.qcoding.businesscopilot.datacopilot.explanation;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryColumn;
import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;
import dev.qcoding.businesscopilot.datacopilot.query.QueryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResultExplanationServiceTest {

    private AiChatService aiChatService;
    private PromptTemplateService promptTemplateService;
    private QueryResultSummarizer summarizer;
    private ResultExplanationService service;

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        // Use the real PromptTemplateService — result-explanation.st lives on the classpath
        promptTemplateService = new PromptTemplateService();
        summarizer = new QueryResultSummarizer();
        service = new ResultExplanationService(aiChatService, promptTemplateService, summarizer);
    }

    // ---- Test: successful explanation ----

    @Test
    @DisplayName("successful model call returns non-degraded explanation")
    void successfulExplanation() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("total", "integer")),
                List.of(new QueryRow(Map.of("total", 100))),
                1, false);
        ResultExplanationRequest request = new ResultExplanationRequest(
                "What is the total sales?", "SELECT SUM(amount) AS total FROM orders", result);

        when(aiChatService.generateText(contains("total sales")))
                .thenReturn("The total sales amount is 100.");

        ResultExplanationResponse response = service.explain(request);

        assertThat(response.degraded()).isFalse();
        assertThat(response.explanation()).isEqualTo("The total sales amount is 100.");
    }

    // ---- Test: empty result returns friendly explanation ----

    @Test
    @DisplayName("empty result returns no-data explanation without calling model")
    void emptyResultReturnsNoDataExplanation() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                List.of(), 0, false);
        ResultExplanationRequest request = new ResultExplanationRequest(
                "show all customers", "SELECT id FROM customers LIMIT 10", result);

        ResultExplanationResponse response = service.explain(request);

        // 空结果不调用模型，直接返回友好解释
        assertThat(response.degraded()).isFalse();
        assertThat(response.explanation()).contains("No matching data");
    }

    @Test
    @DisplayName("empty result with Chinese question returns Chinese explanation")
    void emptyResultChineseQuestion() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                List.of(), 0, false);
        ResultExplanationRequest request = new ResultExplanationRequest(
                "查询所有客户", "SELECT id FROM customers LIMIT 10", result);

        ResultExplanationResponse response = service.explain(request);

        assertThat(response.explanation()).contains("未查询到匹配数据");
    }

    // ---- Test: model failure returns degraded explanation ----

    @Test
    @DisplayName("model failure returns degraded explanation")
    void modelFailureReturnsDegradedExplanation() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("total", "integer")),
                List.of(new QueryRow(Map.of("total", 100))),
                1, false);
        ResultExplanationRequest request = new ResultExplanationRequest(
                "What is the total sales?", "SELECT SUM(amount) AS total FROM orders", result);

        when(aiChatService.generateText(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("model down"));

        ResultExplanationResponse response = service.explain(request);

        assertThat(response.degraded()).isTrue();
        assertThat(response.explanation()).contains("1 rows");
    }

    @Test
    @DisplayName("model failure with Chinese question returns Chinese degraded explanation")
    void modelFailureChineseQuestion() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("total", "integer")),
                List.of(new QueryRow(Map.of("total", 100))),
                1, false);
        ResultExplanationRequest request = new ResultExplanationRequest(
                "上个月销售额是多少", "SELECT SUM(amount) AS total FROM orders", result);

        when(aiChatService.generateText(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("model down"));

        ResultExplanationResponse response = service.explain(request);

        assertThat(response.degraded()).isTrue();
        assertThat(response.explanation()).contains("返回 1 行结果");
    }

    // ---- Test: summary contains columns, row count, and sample rows ----

    @Test
    @DisplayName("summary contains column names, row count, and sample rows")
    void summaryContainsColumnsRowsAndSamples() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("id", "integer"), new QueryColumn("name", "varchar")),
                List.of(
                        new QueryRow(Map.of("id", 1, "name", "Alice")),
                        new QueryRow(Map.of("id", 2, "name", "Bob"))),
                2, false);

        String summary = summarizer.summarize(result);

        assertThat(summary).contains("Columns: [id, name]");
        assertThat(summary).contains("Row count: 2");
        assertThat(summary).contains("Sample rows");
        assertThat(summary).contains("Alice");
        assertThat(summary).contains("Bob");
    }

    @Test
    @DisplayName("summary marks truncated results")
    void summaryMarksTruncated() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                List.of(new QueryRow(Map.of("id", 1))),
                100, true);

        String summary = summarizer.summarize(result);

        assertThat(summary).contains("truncated");
        assertThat(summary).contains("Row count: 100");
    }

    @Test
    @DisplayName("summary handles empty results")
    void summaryHandlesEmpty() {
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                List.of(), 0, false);

        String summary = summarizer.summarize(result);

        assertThat(summary).contains("Columns: [id]");
        assertThat(summary).contains("Row count: 0");
        assertThat(summary).contains("empty");
    }

    @Test
    @DisplayName("summary limits sample rows to 5")
    void summaryLimitsSampleRows() {
        List<QueryRow> rows = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new QueryRow(Map.of("id", i)))
                .toList();
        QueryResultTable result = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                rows, 10, false);

        String summary = summarizer.summarize(result);

        // Only 5 sample rows
        assertThat(summary).contains("5 of 10");
        assertThat(summary).contains("id=1");
        assertThat(summary).contains("id=5");
        assertThat(summary).doesNotContain("id=6");
    }
}
