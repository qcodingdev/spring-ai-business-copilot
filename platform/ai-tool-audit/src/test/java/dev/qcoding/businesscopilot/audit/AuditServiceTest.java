package dev.qcoding.businesscopilot.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    @Test
    void recordDelegatesToRepositoryAndReturnsId() {
        QueryAuditRepository repository = mock(QueryAuditRepository.class);
        when(repository.save(any())).thenReturn(1L);
        AuditService service = new AuditService(repository);

        AuditEvent event = new AuditEvent(
                "req-001", AuditEventType.QUERY_SUCCESS, "q", "sql", "sql",
                AuditStatus.EXECUTED, null, true, 5, null, "gpt-5-mini", 100L);

        Long id = service.record(event);

        assertThat(id).isEqualTo(1L);
        verify(repository).save(event);
    }

    @Test
    void recordSwallowsPersistenceFailure() {
        // 审计写入失败不应中断主流程
        QueryAuditRepository repository = mock(QueryAuditRepository.class);
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        AuditService service = new AuditService(repository);

        AuditEvent event = new AuditEvent(
                "req-002", AuditEventType.QUERY_SUCCESS, "q", "sql", "sql",
                AuditStatus.EXECUTED, null, true, 5, null, "gpt-5-mini", 100L);

        Long id = service.record(event);

        assertThat(id).isNull();
    }

    @Test
    void findRecentDelegates() {
        QueryAuditRepository repository = mock(QueryAuditRepository.class);
        QueryAuditLog log = new QueryAuditLog(
                1L, "req", "http-req", "operator", "q", "sql", "sql", "EXECUTED",
                null, true, "EXECUTED", 1, null, "model", 1L, null);
        when(repository.findRecent(0, 10)).thenReturn(List.of(log));
        AuditService service = new AuditService(repository);

        List<QueryAuditLog> result = service.findRecent(0, 10);

        assertThat(result).containsExactly(log);
        verify(repository, never()).count();
    }

    @Test
    void countDelegates() {
        QueryAuditRepository repository = mock(QueryAuditRepository.class);
        when(repository.count()).thenReturn(42L);
        AuditService service = new AuditService(repository);

        assertThat(service.count()).isEqualTo(42L);
    }
}
