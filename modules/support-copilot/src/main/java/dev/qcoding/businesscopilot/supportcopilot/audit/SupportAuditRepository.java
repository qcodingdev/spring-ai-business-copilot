package dev.qcoding.businesscopilot.supportcopilot.audit;

import java.util.List;

/**
 * Repository for {@link SupportAuditLog} persistence.
 *
 * <p>客服审计仓库接口。定义审计日志的写入和分页查询操作。</p>
 */
public interface SupportAuditRepository {

    SupportAuditLog save(SupportAuditLog log);

    List<SupportAuditLog> findRecent(int page, int size);

    long count();
}
