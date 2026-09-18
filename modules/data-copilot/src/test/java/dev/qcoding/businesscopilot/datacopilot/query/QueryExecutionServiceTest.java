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
        org.mockito.Mockito.doCallRealMethod().when(confirmationService).consumeWithIntent(
                any(), any(), any());
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
                null, "sql-guardrails-v2.0",
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
        when(queryExecutor.execute("cand-1", "operator-1", sql)).thenReturn(table);
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
        assertThat(event.userQuestion()).isEqualTo("已确认的只读业务查询");
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

        when(queryExecutor.execute("cand-1", "operator-1", sql)).thenThrow(
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

        when(queryExecutor.execute("cand-1", "operator-1", sql)).thenThrow(
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
        verify(queryExecutor, never()).execute(any(), any());
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
        when(queryExecutor.execute("cand-1", "operator-1", sql)).thenReturn(table);
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
        when(queryExecutor.execute("cand-1", "operator-1", sql)).thenReturn(table);
        when(explanationService.explain(any())).thenReturn(ResultExplanationResponse.success("ok"));

        service.execute("cand-1", "token-1");

        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        // AuditEvent 字段只有 rowCount，没有完整行数据，结构上保证不记录查询结果
        assertThat(event.rowCount()).isEqualTo(1);
    }

    // ---- 取消执行：对象归属校验（CORE-01 / D-06） ----

    private QueryExecutionService serviceWithActor(String actorId, String... roles) {
        dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider actorProvider = () ->
                new dev.qcoding.businesscopilot.commonsecurity.CurrentActor(
                        actorId, java.util.Arrays.stream(roles)
                        .map(dev.qcoding.businesscopilot.commonsecurity.BusinessRole::valueOf)
                        .collect(java.util.stream.Collectors.toSet()));
        return new QueryExecutionService(
                confirmationService, queryExecutor, explanationService, auditService,
                null, actorProvider, new dev.qcoding.businesscopilot.commonsecurity.DefaultObjectAccessPolicy());
    }

    @Test
    @DisplayName("another operator cannot cancel an execution they do not own")
    void cancelByNonOwnerOperatorIsRejected() {
        QueryExecutionService guarded = serviceWithActor("operator-2", "OPERATOR");
        when(queryExecutor.executionOwner("exec-1")).thenReturn("operator-1");
        when(queryExecutor.cancel("exec-1")).thenReturn(true);

        assertThatThrownBy(() -> guarded.cancel("exec-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        // 越权取消既不执行取消，也留下拒绝审计；原任务状态不被改变
        verify(queryExecutor, never()).cancel("exec-1");
        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AuditEventType.QUERY_CANCEL_DENIED);
        assertThat(event.status()).isEqualTo(AuditStatus.ACCESS_DENIED);
        assertThat(event.creatorActorId()).isEqualTo("operator-1");
        assertThat(event.actionActorId()).isEqualTo("operator-2");
    }

    @Test
    @DisplayName("object owner can cancel and the action is audited")
    void cancelByOwnerIsAllowed() {
        QueryExecutionService guarded = serviceWithActor("operator-1", "OPERATOR");
        when(queryExecutor.executionOwner("exec-1")).thenReturn("operator-1");
        when(queryExecutor.cancel("exec-1")).thenReturn(true);

        assertThat(guarded.cancel("exec-1")).isTrue();

        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AuditEventType.QUERY_CANCELLED);
        assertThat(event.status()).isEqualTo(AuditStatus.CANCELLED);
        assertThat(event.actionActorId()).isEqualTo("operator-1");
    }

    @Test
    @DisplayName("admin cancel capability is explicit and audited")
    void cancelByAdminIsAllowed() {
        QueryExecutionService guarded = serviceWithActor("admin-1", "ADMIN");
        when(queryExecutor.executionOwner("exec-1")).thenReturn("operator-1");
        when(queryExecutor.cancel("exec-1")).thenReturn(true);

        assertThat(guarded.cancel("exec-1")).isTrue();
        verify(queryExecutor).cancel("exec-1");
    }

    @Test
    @DisplayName("cancel without actor context fails closed")
    void cancelWithoutActorContextFailsClosed() {
        when(queryExecutor.executionOwner("exec-1")).thenReturn("operator-1");

        // 旧构造函数未提供操作者上下文：无法校验归属时拒绝取消（fail-closed）
        assertThatThrownBy(() -> service.cancel("exec-1"))
                .isInstanceOf(BusinessException.class);
        verify(queryExecutor, never()).cancel("exec-1");
    }

    @Test
    @DisplayName("cancel of unknown or finished execution returns false without audit noise")
    void cancelUnknownExecutionReturnsFalse() {
        when(queryExecutor.executionOwner("exec-gone")).thenReturn(null);

        assertThat(service.cancel("exec-gone")).isFalse();
        verify(queryExecutor, never()).cancel("exec-gone");
        verify(auditService, never()).record(any());
    }
}
