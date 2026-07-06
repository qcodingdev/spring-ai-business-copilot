package dev.qcoding.businesscopilot.audit;

import java.util.List;

/**
 * Persistence boundary for {@link QueryAuditLog}.
 *
 * <p>审计日志持久化接口。第一版基于 Spring JDBC 实现，
 * 后续可替换为其他存储而不影响上层。</p>
 */
public interface QueryAuditRepository {

    /** Persist a new audit event as a log row. Returns the generated id. */
    Long save(AuditEvent event);

    /** Find recent audit logs ordered by creation time desc. */
    List<QueryAuditLog> findRecent(int page, int size);

    /** Count all audit logs. */
    long count();
}
