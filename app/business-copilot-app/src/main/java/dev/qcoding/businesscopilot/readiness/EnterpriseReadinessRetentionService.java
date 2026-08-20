package dev.qcoding.businesscopilot.readiness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Deletes only snapshots beyond the explicitly bounded evidence-retention window. */
@Service
public class EnterpriseReadinessRetentionService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseReadinessRetentionService.class);
    private final EnterpriseReadinessSnapshotRepository repository;
    private final EnterpriseReadinessProperties properties;

    public EnterpriseReadinessRetentionService(
            EnterpriseReadinessSnapshotRepository repository,
            EnterpriseReadinessProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${business-copilot.enterprise-readiness.cleanup-cron:0 45 3 * * *}")
    public int cleanup() {
        try {
            return repository.deleteGeneratedBefore(
                    Instant.now().minus(properties.snapshotRetention()));
        } catch (RuntimeException ex) {
            log.warn("企业运行就绪快照保留清理失败，现有证据未受影响", ex);
            return 0;
        }
    }
}
