package dev.qcoding.businesscopilot.audit;

import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContext;
import dev.qcoding.businesscopilot.commonweb.request.BusinessRequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcQueryAuditRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcQueryAuditRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new JdbcQueryAuditRepository(jdbcTemplate);
    }

    @AfterEach
    void clearRequestContext() {
        BusinessRequestContextHolder.clear();
    }

    @Test
    void savesSuccessEventAndReturnsId() {
        BusinessRequestContextHolder.set(new BusinessRequestContext("http-req-001", "operator-1"));
        AuditEvent event = new AuditEvent(
                "req-001", AuditEventType.QUERY_SUCCESS,
                "上个月销售额", "SELECT SUM(amount) FROM orders LIMIT 100",
                "SELECT SUM(amount) FROM orders LIMIT 100",
                AuditStatus.EXECUTED, null, true,
                5, null, "gpt-5-mini", 250L);

        // Use lenient matching: anyString, eq(Long.class), then varargs of Object...
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(42L);

        Long id = repository.save(event);

        assertThat(id).isEqualTo(42L);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class),
                eq("req-001"),
                eq("http-req-001"),
                eq("operator-1"),
                eq("上个月销售额"),
                eq("SELECT SUM(amount) FROM orders LIMIT 100"),
                eq("SELECT SUM(amount) FROM orders LIMIT 100"),
                eq("EXECUTED"),
                eq(null),
                eq(true),
                eq("QUERY_SUCCESS"),
                eq(5),
                eq(null),
                eq("gpt-5-mini"),
                eq(250L),
                eq("operator-1"),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq("zh-CN"),
                any(java.sql.Timestamp.class));
    }

    @Test
    void savesValidationFailureEvent() {
        AuditEvent event = new AuditEvent(
                "req-002", AuditEventType.QUERY_FAILURE,
                "delete all", "DELETE FROM customers",
                null,
                AuditStatus.VALIDATION_FAILED, "FORBIDDEN_KEYWORD: delete",
                false, null, "Validation failed", "gpt-5-mini", 80L);

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);

        Long id = repository.save(event);

        assertThat(id).isEqualTo(7L);
    }

    @Test
    void savesExecutionFailureEvent() {
        AuditEvent event = new AuditEvent(
                "req-003", AuditEventType.QUERY_FAILURE,
                "broken query", "SELECT x FROM orders LIMIT 10",
                "SELECT x FROM orders LIMIT 10",
                AuditStatus.EXECUTION_FAILED, null, true,
                null, "column x does not exist", "gpt-5-mini", 120L);

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(9L);

        Long id = repository.save(event);

        assertThat(id).isEqualTo(9L);
    }

    @Test
    void findsRecentLogs() {
        QueryAuditLog sample = new QueryAuditLog(
                1L, "req-001", "http-req-001", "operator", "question", "sql", "sql",
                "EXECUTED", null, true, "EXECUTED", 5,
                null, "gpt-5-mini", 100L, java.time.Instant.now());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyInt(), anyInt()))
                .thenReturn(List.of(sample));

        List<QueryAuditLog> result = repository.findRecent(0, 20);

        assertThat(result).hasSize(1).first().isSameAs(sample);
    }

    @Test
    void countReturnsPersistedValue() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(99L);

        assertThat(repository.count()).isEqualTo(99L);
    }
}
