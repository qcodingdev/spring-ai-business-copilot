package dev.qcoding.businesscopilot.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Application service that records audit events and exposes recent logs.
 *
 * <p>审计服务。无论查询成功还是失败，都必须记录审计；用户未确认也要记录状态。
 * 关键原则：审计日志不记录完整查询结果，也不记录敏感字段明文值——
 * 调用方传入 {@link AuditEvent} 时必须保证不含敏感数据。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final QueryAuditRepository repository;

    public AuditService(QueryAuditRepository repository) {
        this.repository = repository;
    }

    /** Persist an audit event. Failures here must never break the user's main flow. */
    public Long record(AuditEvent event) {
        try {
            return repository.save(event);
        } catch (RuntimeException ex) {
            // 审计写入失败不应中断主流程，但要记录日志便于排查
            log.error("Failed to persist audit event for requestId={}", event.requestId(), ex);
            return null;
        }
    }

    /** Find recent audit logs for the dashboard preview. */
    public List<QueryAuditLog> findRecent(int page, int size) {
        return repository.findRecent(page, size);
    }

    /** Total audit log count. */
    public long count() {
        return repository.count();
    }
}
