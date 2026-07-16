package dev.qcoding.businesscopilot.supportcopilot.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Service for recording Support Copilot audit events.
 *
 * <p>客服审计服务。提供便捷的记录方法供 controller 和 service 使用。</p>
 */
public class SupportAuditService {

    private static final Logger log = LoggerFactory.getLogger(SupportAuditService.class);

    private final SupportAuditRepository repository;

    public SupportAuditService(SupportAuditRepository repository) {
        this.repository = repository;
    }

    public void record(SupportAuditLog auditLog) {
        try {
            recordRequired(auditLog);
        } catch (Exception e) {
            log.error("Failed to record support audit log", e);
        }
    }

    public void recordRequired(SupportAuditLog auditLog) {
        repository.save(auditLog);
        log.debug("Support audit recorded: eventType={}, requestId={}",
                auditLog.eventType(), auditLog.requestId());
    }

    public List<SupportAuditLog> findRecent(int page, int size) {
        return repository.findRecent(page, size);
    }

    public long count() {
        return repository.count();
    }
}
