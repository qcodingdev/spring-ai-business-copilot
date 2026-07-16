package dev.qcoding.businesscopilot.datacopilot.query;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SensitiveDataMasker;
import dev.qcoding.businesscopilot.guardrails.SensitiveFieldPolicy;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import dev.qcoding.businesscopilot.guardrails.SqlValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcReadOnlyQueryExecutorTest {

    private JdbcTemplate jdbcTemplate;
    private SqlGuardrailService guardrailService;
    private GuardrailsProperties guardrailsProperties;
    private SensitiveDataMasker masker;
    private QueryExecutionProperties queryProperties;
    private JdbcReadOnlyQueryExecutor executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        guardrailService = mock(SqlGuardrailService.class);
        guardrailsProperties = new GuardrailsProperties(null, null, null, 0, true);
        SensitiveFieldPolicy policy = new SensitiveFieldPolicy(guardrailsProperties);
        masker = new SensitiveDataMasker(policy);
        queryProperties = new QueryExecutionProperties(30, 100);

        executor = new JdbcReadOnlyQueryExecutor(
                jdbcTemplate, guardrailService, guardrailsProperties,
                masker, queryProperties);
    }

    // ---- Helper: mock a ResultSet with given columns and rows ----

    private ResultSet mockResultSet(List<String> colNames, List<String> colTypes,
                                    List<List<Object>> rows) throws SQLException {
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(colNames.size());
        for (int i = 0; i < colNames.size(); i++) {
            when(metaData.getColumnLabel(i + 1)).thenReturn(colNames.get(i));
            when(metaData.getColumnTypeName(i + 1)).thenReturn(colTypes.get(i));
        }

        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metaData);

        // Track the current row index so getObject can return the right value per row
        final int[] currentRow = {-1};
        when(rs.next()).thenAnswer(invocation -> {
            currentRow[0]++;
            return currentRow[0] < rows.size();
        });
        when(rs.getObject(anyInt())).thenAnswer(invocation -> {
            int colIndex = invocation.getArgument(0);
            if (currentRow[0] < 0 || currentRow[0] >= rows.size()) return null;
            return rows.get(currentRow[0]).get(colIndex - 1);
        });

        return rs;
    }

    // ---- Test: successful query returns columns and rows ----

    @Test
    @DisplayName("successful query returns columns and rows")
    void successfulQueryReturnsColumnsAndRows() throws SQLException {
        String sql = "SELECT id, name FROM customers LIMIT 10";

        // Guardrails pass
        when(guardrailService.validate(sql, guardrailsProperties))
                .thenReturn(SqlValidationResult.pass(sql));

        // Mock JdbcTemplate to simulate query execution
        when(jdbcTemplate.execute(any(StatementCallback.class))).thenAnswer(invocation -> {
            StatementCallback<QueryResultTable> callback = invocation.getArgument(0);
            Statement stmt = mock(Statement.class);
            ResultSet rs = mockResultSet(
                    List.of("id", "name"),
                    List.of("integer", "varchar"),
                    List.of(
                            List.of(1, "Alice"),
                            List.of(2, "Bob")));
            when(stmt.executeQuery(sql)).thenReturn(rs);
            QueryResultTable table = callback.doInStatement(stmt);
            verify(stmt).setQueryTimeout(30);
            verify(stmt).setMaxRows(101);
            verify(stmt).setFetchSize(50);
            return table;
        });

        QueryResultTable result = executor.execute(sql);

        assertThat(result.columns()).hasSize(2);
        assertThat(result.columns().get(0).name()).isEqualTo("id");
        assertThat(result.columns().get(1).name()).isEqualTo("name");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    // ---- Test: max rows exceeded sets truncated=true ----

    @Test
    @DisplayName("max rows exceeded sets truncated=true")
    void maxRowsExceededSetsTruncated() throws SQLException {
        // Override to maxRows=2
        QueryExecutionProperties smallProps = new QueryExecutionProperties(30, 2);
        executor = new JdbcReadOnlyQueryExecutor(
                jdbcTemplate, guardrailService, guardrailsProperties,
                masker, smallProps);

        String sql = "SELECT id FROM customers LIMIT 100";

        when(guardrailService.validate(sql, guardrailsProperties))
                .thenReturn(SqlValidationResult.pass(sql));

        when(jdbcTemplate.execute(any(StatementCallback.class))).thenAnswer(invocation -> {
            StatementCallback<QueryResultTable> callback = invocation.getArgument(0);
            Statement stmt = mock(Statement.class);
            ResultSet rs = mockResultSet(
                    List.of("id"),
                    List.of("integer"),
                    List.of(
                            List.of(1),
                            List.of(2),
                            List.of(3)));
            when(stmt.executeQuery(sql)).thenReturn(rs);
            return callback.doInStatement(stmt);
        });

        QueryResultTable result = executor.execute(sql);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    // ---- Test: phone/email masked before return ----

    @Test
    @DisplayName("phone and email values are masked before return")
    void phoneAndEmailMaskedBeforeReturn() throws SQLException {
        String sql = "SELECT name, phone, email FROM customers LIMIT 10";

        when(guardrailService.validate(sql, guardrailsProperties))
                .thenReturn(SqlValidationResult.pass(sql));

        when(jdbcTemplate.execute(any(StatementCallback.class))).thenAnswer(invocation -> {
            StatementCallback<QueryResultTable> callback = invocation.getArgument(0);
            Statement stmt = mock(Statement.class);
            ResultSet rs = mockResultSet(
                    List.of("name", "phone", "email"),
                    List.of("varchar", "varchar", "varchar"),
                    List.of(List.of("Alice", "13812345678", "user001@example.com")));
            when(stmt.executeQuery(sql)).thenReturn(rs);
            return callback.doInStatement(stmt);
        });

        QueryResultTable result = executor.execute(sql);

        assertThat(result.rows()).hasSize(1);
        Map<String, Object> values = result.rows().get(0).values();
        assertThat(values.get("name")).isEqualTo("Alice");
        assertThat(values.get("phone")).isEqualTo("138****5678");
        assertThat(values.get("email")).isEqualTo("u***@example.com");
    }

    // ---- Test: non-readonly SQL rejected by second guardrails ----

    @Test
    @DisplayName("non-readonly SQL rejected by second guardrails check")
    void nonReadonlySqlRejectedBySecondGuardrails() {
        String sql = "DELETE FROM customers WHERE id = 1";

        when(guardrailService.validate(sql, guardrailsProperties))
                .thenReturn(SqlValidationResult.fail(sql, List.of()));

        assertThatThrownBy(() -> executor.execute(sql))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SQL 未通过安全校验")
                .hasMessageNotContaining("DELETE");
    }

    // ---- Test: SQL exception translated to user-friendly message ----

    @Test
    @DisplayName("SQL timeout exception translated to user-friendly message")
    void sqlTimeoutExceptionTranslated() {
        String sql = "SELECT id FROM customers LIMIT 10";

        when(guardrailService.validate(sql, guardrailsProperties))
                .thenReturn(SqlValidationResult.pass(sql));

        // JdbcTemplate wraps SQLException into UncategorizedSQLException
        SQLException sqlTimeout = new SQLTimeoutException("timeout", "57014", 0);
        when(jdbcTemplate.execute(any(StatementCallback.class)))
                .thenThrow(new UncategorizedSQLException("execute", sql, sqlTimeout));

        assertThatThrownBy(() -> executor.execute(sql))
                .isInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("查询超时");
    }

    // ---- Test: default properties ----

    @Test
    @DisplayName("default query execution properties")
    void defaultQueryExecutionProperties() {
        QueryExecutionProperties defaults = new QueryExecutionProperties(0, 0);
        assertThat(defaults.queryTimeoutSeconds()).isEqualTo(30);
        assertThat(defaults.maxRows()).isEqualTo(100);
        assertThat(defaults.fetchSize()).isEqualTo(50);
        assertThat(defaults.maxColumns()).isEqualTo(50);
        assertThat(defaults.maxResultBytes()).isEqualTo(1024 * 1024);
    }

    @Test
    @DisplayName("custom query execution properties")
    void customQueryExecutionProperties() {
        QueryExecutionProperties custom = new QueryExecutionProperties(60, 500, 25, 20, 4096);
        assertThat(custom.queryTimeoutSeconds()).isEqualTo(60);
        assertThat(custom.maxRows()).isEqualTo(500);
        assertThat(custom.fetchSize()).isEqualTo(25);
        assertThat(custom.maxColumns()).isEqualTo(20);
        assertThat(custom.maxResultBytes()).isEqualTo(4096);
    }

    @Test
    @DisplayName("result column count exceeding cap is rejected")
    void resultColumnCountExceedingCapRejected() throws SQLException {
        QueryExecutionProperties smallProps =
                new QueryExecutionProperties(30, 100, 10, 1, 1024);
        executor = new JdbcReadOnlyQueryExecutor(
                jdbcTemplate, guardrailService, guardrailsProperties, masker, smallProps);
        String sql = "SELECT id, name FROM public.customers LIMIT 10";
        when(guardrailService.validate(sql, guardrailsProperties))
                .thenReturn(SqlValidationResult.pass(sql));
        when(jdbcTemplate.execute(any(StatementCallback.class))).thenAnswer(invocation -> {
            StatementCallback<QueryResultTable> callback = invocation.getArgument(0);
            Statement stmt = mock(Statement.class);
            ResultSet rs = mockResultSet(
                    List.of("id", "name"),
                    List.of("integer", "varchar"),
                    List.of(List.of(1, "Alice")));
            when(stmt.executeQuery(sql)).thenReturn(rs);
            return callback.doInStatement(stmt);
        });

        assertThatThrownBy(() -> executor.execute(sql))
                .isInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("列数超过安全上限");
    }

    @Test
    @DisplayName("result bytes exceeding cap are rejected")
    void resultBytesExceedingCapRejected() throws SQLException {
        QueryExecutionProperties smallProps =
                new QueryExecutionProperties(30, 100, 10, 10, 32);
        executor = new JdbcReadOnlyQueryExecutor(
                jdbcTemplate, guardrailService, guardrailsProperties, masker, smallProps);
        String sql = "SELECT name FROM public.customers LIMIT 10";
        when(guardrailService.validate(sql, guardrailsProperties))
                .thenReturn(SqlValidationResult.pass(sql));
        when(jdbcTemplate.execute(any(StatementCallback.class))).thenAnswer(invocation -> {
            StatementCallback<QueryResultTable> callback = invocation.getArgument(0);
            Statement stmt = mock(Statement.class);
            ResultSet rs = mockResultSet(
                    List.of("name"),
                    List.of("varchar"),
                    List.of(List.of("A".repeat(64))));
            when(stmt.executeQuery(sql)).thenReturn(rs);
            return callback.doInStatement(stmt);
        });

        assertThatThrownBy(() -> executor.execute(sql))
                .isInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("大小超过安全上限");
    }
}
