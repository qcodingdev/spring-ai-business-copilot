package dev.qcoding.businesscopilot.datacopilot.query;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SensitiveDataMasker;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import dev.qcoding.businesscopilot.guardrails.SqlValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC-based read-only query executor with defensive guardrails and result masking.
 *
 * <p>基于 Spring JDBC 的只读查询执行器。只负责执行与脱敏，不写审计——
 * 审计由上层编排服务（QueryExecutionService）统一处理，确保执行阶段能携带完整的
 * requestId/userQuestion/modelName 上下文。核心安全机制：
 * <ul>
 *   <li>执行前再次调用 SqlGuardrailService 做防御式二次校验——即使生成阶段已通过，
 *       执行前仍需确认，防止候选被篡改或存储后规则变更。</li>
 *   <li>设置 query timeout 防止慢查询占用连接。</li>
 *   <li>设置 JDBC max rows/fetch size，并限制返回列数和结果字节数。</li>
 *   <li>查询结果返回前调用 SensitiveDataMasker 对 phone/email 等字段脱敏。</li>
 *   <li>SQL 异常转换成用户可理解的 BusinessException，不暴露堆栈。</li>
 * </ul></p>
 */
public class JdbcReadOnlyQueryExecutor implements ReadOnlyQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcReadOnlyQueryExecutor.class);

    private final JdbcTemplate jdbcTemplate;
    private final SqlGuardrailService guardrailService;
    private final GuardrailsProperties guardrailsProperties;
    private final SensitiveDataMasker masker;
    private final QueryExecutionProperties queryProperties;
    private final Map<String, Statement> activeStatements = new ConcurrentHashMap<>();

    public JdbcReadOnlyQueryExecutor(JdbcTemplate jdbcTemplate,
                                      SqlGuardrailService guardrailService,
                                      GuardrailsProperties guardrailsProperties,
                                      SensitiveDataMasker masker,
                                      QueryExecutionProperties queryProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.guardrailService = guardrailService;
        this.guardrailsProperties = guardrailsProperties;
        this.masker = masker;
        this.queryProperties = queryProperties;
    }

    @Override
    public QueryResultTable execute(String sql) {
        return execute(null, sql);
    }

    @Override
    public QueryResultTable execute(String executionId, String sql) {
        // 1. 防御式二次 guardrails 校验
        SqlValidationResult validationResult = guardrailService.validate(sql, guardrailsProperties);
        if (!validationResult.passed()) {
            String violationDetails = String.join("; ",
                    validationResult.violations().stream()
                            .map(v -> v.code() + ": " + v.message())
                            .toList());
            log.warn("SQL 执行前二次安全校验未通过：{}", violationDetails);
            throw new BusinessException(ErrorCode.SQL_GUARDRAIL_VIOLATION,
                    "SQL 未通过安全校验");
        }

        // 2. 执行查询（设置超时和最大行数）；SQL 异常转换成用户可理解的 BusinessException，不暴露堆栈
        try {
            return jdbcTemplate.execute(new StatementCallback<>() {
                @Override
                public QueryResultTable doInStatement(Statement stmt) throws SQLException {
                    stmt.setQueryTimeout(queryProperties.queryTimeoutSeconds());
                    stmt.setMaxRows(jdbcMaxRows());
                    stmt.setFetchSize(Math.min(queryProperties.fetchSize(), jdbcMaxRows()));
                    if (executionId != null) {
                        activeStatements.put(executionId, stmt);
                    }
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        return mapResultSet(rs);
                    } finally {
                        if (executionId != null) {
                            activeStatements.remove(executionId, stmt);
                        }
                    }
                }
            });
        } catch (DataAccessException ex) {
            // JdbcTemplate 把 SQLException 包装成 DataAccessException，这里取出原始 SQLException
            SQLException sqlEx = extractSqlException(ex);
            String userMessage = sqlEx != null ? translateSQLException(sqlEx) : "查询执行失败";
            log.error("查询执行失败：{}", userMessage, ex);
            throw new QueryExecutionException(userMessage, ex);
        }
    }

    @Override
    public boolean cancel(String executionId) {
        Statement statement = activeStatements.get(executionId);
        if (statement == null) {
            return false;
        }
        try {
            statement.cancel();
            return true;
        } catch (SQLException ex) {
            log.warn("取消查询失败：executionId={}", executionId);
            return false;
        }
    }

    /** Extract the underlying SQLException from a Spring DataAccessException if present. */
    private SQLException extractSqlException(DataAccessException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    /** Map a ResultSet to a QueryResultTable, applying masking and truncation. */
    private QueryResultTable mapResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        if (columnCount > queryProperties.maxColumns()) {
            throw new QueryExecutionException("查询结果列数超过安全上限，请减少查询字段");
        }

        // 提取列描述
        List<QueryColumn> columns = new ArrayList<>();
        long resultBytes = 0;
        for (int i = 1; i <= columnCount; i++) {
            String columnLabel = metaData.getColumnLabel(i);
            String columnType = metaData.getColumnTypeName(i);
            columns.add(new QueryColumn(columnLabel, columnType));
            resultBytes += estimateBytes(columnLabel);
            resultBytes += estimateBytes(columnType);
        }
        enforceResultByteLimit(resultBytes);

        // 提取行数据，应用 max rows 和脱敏
        List<QueryRow> rows = new ArrayList<>();
        int maxRows = queryProperties.maxRows();
        boolean truncated = false;

        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnLabel(i);
                Object rawValue = rs.getObject(i);
                Object maskedValue = maskIfNeeded(columnName, rawValue);
                resultBytes += estimateBytes(columnName);
                resultBytes += estimateBytes(maskedValue);
                enforceResultByteLimit(resultBytes);
                values.put(columnName, maskedValue);
            }
            rows.add(new QueryRow(values));
        }

        return new QueryResultTable(columns, rows, rows.size(), truncated);
    }

    private int jdbcMaxRows() {
        return queryProperties.maxRows() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : queryProperties.maxRows() + 1;
    }

    private void enforceResultByteLimit(long resultBytes) {
        if (resultBytes > queryProperties.maxResultBytes()) {
            throw new QueryExecutionException("查询结果大小超过安全上限，请缩小查询范围");
        }
    }

    private long estimateBytes(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Apply masking if the column is sensitive; pass through otherwise. */
    private Object maskIfNeeded(String columnName, Object value) {
        if (value == null) return null;
        if (value instanceof String stringValue) {
            return masker.mask(columnName, stringValue);
        }
        // 非字符串类型不做脱敏
        return value;
    }

    /** Translate a SQLException into a user-friendly message without internals. */
    private String translateSQLException(SQLException ex) {
        String sqlState = ex.getSQLState();
        // 常见错误类别映射
        if ("57014".equals(sqlState)) {
            return "查询超时，请缩小查询范围后重试";
        }
        if (sqlState != null && sqlState.startsWith("42")) {
            return "SQL 语法错误或对象不存在";
        }
        if (sqlState != null && sqlState.startsWith("08")) {
            return "数据库连接异常，请稍后重试";
        }
        // 默认：不暴露原始错误
        return "查询执行失败";
    }
}
