package dev.qcoding.businesscopilot.datacopilot.query;

import dev.qcoding.businesscopilot.audit.AuditEvent;
import dev.qcoding.businesscopilot.audit.AuditEventType;
import dev.qcoding.businesscopilot.audit.AuditService;
import dev.qcoding.businesscopilot.audit.AuditStatus;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidate;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateNotExecutableException;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationResponse;
import dev.qcoding.businesscopilot.datacopilot.explanation.ResultExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link QueryExecutionService} focused on the audit lifecycle.
 *
 * <p>查询执行编排服务测试。覆盖审计生命周期：确认失败、二次 guardrails 失败、执行成功（含 rowCount）、执行失败（含错误摘要）。</p>
 */
class QueryExecutionServiceTest {

    private SqlConfirmationService confirmationService;
    private ReadOnlyQueryExecutor queryExecutor;
    private ResultExplanationService explanationService;
    private AuditService auditService;
    private QueryExecutionService service;

    @BeforeEach
    void setUp() {
        confirmationService = mock(SqlConfirmationService.class);
        queryExecutor = mock(ReadOnlyQueryExecutor.class);
        explanationService = mock(ResultExplanationService.class);
        auditService = mock(AuditService.class);
        service = new QueryExecutionService(
                confirmationService, queryExecutor, explanationService, auditService);
    }

    private SqlCandidate candidateWithAuditContext(String sql) {
        Instant now = Instant.now();
        return new SqlCandidate(
                "cand-1", sql, null, null,
                dev.qcoding.businesscopilot.datacopilot.confirmation.SqlCandidateStatus.CONSUMED,
                "operator-1", "req-001", "gpt-5-mini",
                "data-copilot/sql-generation.st", "v1", null,
                null, "sql-guardrails-v1.1",
                now, now.plusSeconds(600), now, "operator-1");
    }

    // ---- 执行成功：写审计并包含 rowCount ----

    @Test
    @DisplayName("execution success records audit with rowCount")
    void executionSuccessRecordsAuditWithRowCount() {
        String sql = "SELECT id FROM customers LIMIT 10";
        SqlCandidate candidate = candidateWithAuditContext(sql);
        when(confirmationService.confirmAndConsume("cand-1", "token-1")).thenReturn(candidate);

        QueryResultTable table = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                List.of(new QueryRow(java.util.Map.of("id", 1)),
                        new QueryRow(java.util.Map.of("id", 2))),
                2, false);
        when(queryExecutor.execute(sql)).thenReturn(table);
        when(explanationService.explain(any())).thenReturn(ResultExplanationResponse.success("ok"));

        service.execute("cand-1", "token-1");

        // 验证审计：成功 + rowCount=2 + 携带 requestId/userQuestion/modelName 上下文
        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AuditEventType.QUERY_SUCCESS);
        assertThat(event.status()).isEqualTo(AuditStatus.EXECUTED);
        assertThat(event.rowCount()).isEqualTo(2);
        assertThat(event.requestId()).isEqualTo("req-001");
        assertThat(event.userQuestion()).isEqualTo("Confirmed read-only business query");
        assertThat(event.modelName()).isEqualTo("gpt-5-mini");
        assertThat(event.confirmed()).isTrue();
        assertThat(event.finalSql()).isEqualTo(sql);
        assertThat(event.errorMessage()).isNull();
    }

    // ---- 执行失败：写审计并包含错误摘要 ----

    @Test
    @DisplayName("execution failure records a stable audit outcome without provider details")
    void executionFailureRecordsStableAuditOutcome() {
        String sql = "SELECT bad_col FROM customers LIMIT 10";
        SqlCandidate candidate = candidateWithAuditContext(sql);
        when(confirmationService.confirmAndConsume("cand-1", "token-1")).thenReturn(candidate);

        when(queryExecutor.execute(sql)).thenThrow(
                new QueryExecutionException("查询执行失败"));

        assertThatThrownBy(() -> service.execute("cand-1", "token-1"))
                .isInstanceOf(QueryExecutionException.class);

        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AuditEventType.QUERY_FAILURE);
        assertThat(event.status()).isEqualTo(AuditStatus.EXECUTION_FAILED);
        assertThat(event.errorMessage()).isNull();
        assertThat(event.rowCount()).isNull();
        assertThat(event.requestId()).isEqualTo("req-001");
        assertThat(event.modelName()).isEqualTo("gpt-5-mini");
    }

    // ---- 二次 guardrails 失败：写审计 ----

    @Test
    @DisplayName("second guardrails failure records validation-failed audit")
    void secondGuardrailsFailureRecordsAudit() {
        String sql = "DELETE FROM customers";
        SqlCandidate candidate = candidateWithAuditContext(sql);
        when(confirmationService.confirmAndConsume("cand-1", "token-1")).thenReturn(candidate);

        when(queryExecutor.execute(sql)).thenThrow(
                new BusinessException(ErrorCode.SQL_GUARDRAIL_VIOLATION, "rejected by guardrails"));

        assertThatThrownBy(() -> service.execute("cand-1", "token-1"))
                .isInstanceOf(BusinessException.class);

        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AuditEventType.QUERY_FAILURE);
        assertThat(event.status()).isEqualTo(AuditStatus.VALIDATION_FAILED);
        assertThat(event.validationErrors()).isNull();
        assertThat(event.violationCodes()).isEqualTo("SECONDARY_GUARDRAIL_REJECTED");
    }

    // ---- 确认失败（取消）：写 not-confirmed 审计 ----

    @Test
    @DisplayName("confirmation failure records not-confirmed audit")
    void confirmationFailureRecordsNotConfirmedAudit() {
        when(confirmationService.confirmAndConsume("cand-1", "wrong-token"))
                .thenThrow(new SqlCandidateNotExecutableException("Invalid confirmation token"));

        assertThatThrownBy(() -> service.execute("cand-1", "wrong-token"))
                .isInstanceOf(SqlCandidateNotExecutableException.class);

        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AuditEventType.QUERY_NOT_CONFIRMED);
        assertThat(event.status()).isEqualTo(AuditStatus.NOT_CONFIRMED);
        assertThat(event.confirmed()).isFalse();
        // 确认失败时不执行查询
        verify(queryExecutor, never()).execute(any());
    }

    // ---- 执行成功返回 table + explanation ----

    @Test
    @DisplayName("execute returns table and explanation on success")
    void executeReturnsTableAndExplanation() {
        String sql = "SELECT id FROM customers LIMIT 10";
        SqlCandidate candidate = candidateWithAuditContext(sql);
        when(confirmationService.confirmAndConsume("cand-1", "token-1")).thenReturn(candidate);

        QueryResultTable table = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                List.of(new QueryRow(java.util.Map.of("id", 1))), 1, false);
        when(queryExecutor.execute(sql)).thenReturn(table);
        when(explanationService.explain(any())).thenReturn(ResultExplanationResponse.success("found 1 row"));

        var response = service.execute("cand-1", "token-1");

        assertThat(response.table().rowCount()).isEqualTo(1);
        assertThat(response.explanation().explanation()).isEqualTo("found 1 row");
        assertThat(response.explanation().degraded()).isFalse();
    }

    // ---- 审计记录不包含完整查询结果，也不包含敏感原始值 ----

    @Test
    @DisplayName("audit event does not carry full result rows")
    void auditEventHasNoResultRows() {
        String sql = "SELECT id FROM customers LIMIT 10";
        SqlCandidate candidate = candidateWithAuditContext(sql);
        when(confirmationService.confirmAndConsume("cand-1", "token-1")).thenReturn(candidate);

        QueryResultTable table = new QueryResultTable(
                List.of(new QueryColumn("id", "integer")),
                List.of(new QueryRow(java.util.Map.of("id", 1))), 1, false);
        when(queryExecutor.execute(sql)).thenReturn(table);
        when(explanationService.explain(any())).thenReturn(ResultExplanationResponse.success("ok"));

        service.execute("cand-1", "token-1");

        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        // AuditEvent 字段只有 rowCount，没有完整行数据，结构上保证不记录查询结果
        assertThat(event.rowCount()).isEqualTo(1);
    }
}
