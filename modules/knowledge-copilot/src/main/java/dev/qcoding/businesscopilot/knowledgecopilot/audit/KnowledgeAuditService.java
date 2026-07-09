package dev.qcoding.businesscopilot.knowledgecopilot.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Application service for Knowledge Copilot audit logging.
 *
 * <p>知识问答审计服务。记录问答全流程关键元信息，支持分页查询。
 * 审计写入失败不中断主流程。</p>
 */
public class KnowledgeAuditService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAuditService.class);

    private final KnowledgeQaAuditRepository repository;

    public KnowledgeAuditService(KnowledgeQaAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist an audit log. Failures must never break the user's main flow.
     *
     * @param auditLog the audit entry to persist
     * @return the generated id, or null on failure
     */
    public Long record(KnowledgeQaAuditLog auditLog) {
        try {
            return repository.save(auditLog);
        } catch (RuntimeException ex) {
            log.error("Failed to persist knowledge QA audit log for requestId={}", auditLog.requestId(), ex);
            return null;
        }
    }

    /** Find recent audit logs for the dashboard preview. */
    public List<KnowledgeQaAuditLog> findRecent(int page, int size) {
        return repository.findRecent(page, size);
    }

    /** Total audit log count. */
    public long count() {
        return repository.count();
    }
}
