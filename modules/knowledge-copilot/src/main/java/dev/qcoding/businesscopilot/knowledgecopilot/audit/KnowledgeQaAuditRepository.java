package dev.qcoding.businesscopilot.knowledgecopilot.audit;

import java.util.List;

/**
 * Persistence boundary for {@link KnowledgeQaAuditLog}.
 *
 * <p>知识问答审计日志持久化接口。基于 Spring JDBC 实现。</p>
 */
public interface KnowledgeQaAuditRepository {

    /** Persist a new audit log. Returns the generated id. */
    Long save(KnowledgeQaAuditLog log);

    /** Find recent audit logs ordered by creation time desc, with pagination. */
    List<KnowledgeQaAuditLog> findRecent(int page, int size);

    /** Count all audit logs. */
    long count();
}
