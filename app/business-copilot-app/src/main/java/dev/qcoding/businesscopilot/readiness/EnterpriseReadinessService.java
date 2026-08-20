package dev.qcoding.businesscopilot.readiness;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.api.PageResponse;
import dev.qcoding.businesscopilot.demo.RuntimeModeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Calculates live readiness and creates server-side, immutable delivery evidence. */
@Service
public class EnterpriseReadinessService {

    private static final int SCHEMA_VERSION = 1;
    private static final List<CheckDefinition> DEFINITIONS = List.of(
            blocker("DATA_STALE_HANDOFF_CLAIMS", EnterpriseReadiness.Module.DATA,
                    "/data?tab=handoff", Window.STALE),
            warning("DATA_EXPIRED_RESULTS", EnterpriseReadiness.Module.DATA,
                    "/data?tab=records", Window.STALE),
            blocker("KNOWLEDGE_STALE_SYNC_RUNS", EnterpriseReadiness.Module.KNOWLEDGE,
                    "/knowledge?tab=sources", Window.STALE),
            warning("KNOWLEDGE_FAILED_SYNC_RUNS", EnterpriseReadiness.Module.KNOWLEDGE,
                    "/knowledge?tab=sources", Window.FAILURE),
            blocker("KNOWLEDGE_BLOCKED_DOCUMENTS", EnterpriseReadiness.Module.KNOWLEDGE,
                    "/knowledge?tab=sources", Window.STALE),
            blocker("SUPPORT_UNKNOWN_WRITEBACKS", EnterpriseReadiness.Module.SUPPORT,
                    "/support?tab=review", Window.NONE),
            blocker("SUPPORT_STALE_WRITEBACKS", EnterpriseReadiness.Module.SUPPORT,
                    "/support?tab=review", Window.STALE),
            warning("SUPPORT_BREACHED_SLA", EnterpriseReadiness.Module.SUPPORT,
                    "/support?tab=quality", Window.NONE),
            blocker("REPORT_STALE_SCHEDULE_CLAIMS", EnterpriseReadiness.Module.REPORT,
                    "/report?tab=schedules", Window.STALE),
            warning("REPORT_FAILED_RUNS", EnterpriseReadiness.Module.REPORT,
                    "/report?tab=schedules", Window.FAILURE),
            warning("REPORT_OVERDUE_REVIEWS", EnterpriseReadiness.Module.REPORT,
                    "/report?tab=records", Window.REVIEW),
            warning("HR_OVERDUE_ASSESSMENT_REVIEWS", EnterpriseReadiness.Module.HR,
                    "/hr?section=recruiting&tab=assessment", Window.REVIEW),
            warning("HR_OVERDUE_ONBOARDING_TASKS", EnterpriseReadiness.Module.HR,
                    "/hr?section=employee&tab=onboarding", Window.REVIEW));

    private final EnterpriseReadinessProbeRepository probeRepository;
    private final EnterpriseReadinessSnapshotRepository snapshotRepository;
    private final EnterpriseReadinessProperties properties;
    private final RuntimeModeProperties runtimeModeProperties;
    private final CurrentActorProvider actorProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public EnterpriseReadinessService(
            EnterpriseReadinessProbeRepository probeRepository,
            EnterpriseReadinessSnapshotRepository snapshotRepository,
            EnterpriseReadinessProperties properties,
            RuntimeModeProperties runtimeModeProperties,
            CurrentActorProvider actorProvider,
            ObjectMapper objectMapper) {
        this(probeRepository, snapshotRepository, properties, runtimeModeProperties,
                actorProvider, objectMapper, Clock.systemUTC());
    }

    EnterpriseReadinessService(
            EnterpriseReadinessProbeRepository probeRepository,
            EnterpriseReadinessSnapshotRepository snapshotRepository,
            EnterpriseReadinessProperties properties,
            RuntimeModeProperties runtimeModeProperties,
            CurrentActorProvider actorProvider,
            ObjectMapper objectMapper,
            Clock clock) {
        this.probeRepository = probeRepository;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
        this.runtimeModeProperties = runtimeModeProperties;
        this.actorProvider = actorProvider;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public EnterpriseReadiness.Assessment assess() {
        Instant generatedAt = clock.instant();
        Map<String, Long> counts = probeRepository.probe(generatedAt, properties);
        validateProbeResult(counts);
        List<EnterpriseReadiness.Check> checks = DEFINITIONS.stream()
                .map(definition -> toCheck(definition, counts.getOrDefault(definition.checkId(), 0L)))
                .toList();
        int blockers = Math.toIntExact(checks.stream()
                .filter(check -> check.status() == EnterpriseReadiness.CheckStatus.BLOCKER).count());
        int warnings = Math.toIntExact(checks.stream()
                .filter(check -> check.status() == EnterpriseReadiness.CheckStatus.WARNING).count());
        int passed = checks.size() - blockers - warnings;
        EnterpriseReadiness.OverallStatus status = blockers > 0
                ? EnterpriseReadiness.OverallStatus.BLOCKED
                : warnings > 0
                        ? EnterpriseReadiness.OverallStatus.ATTENTION
                        : EnterpriseReadiness.OverallStatus.READY;
        String runtimeMode = runtimeModeProperties.mode().propertyValue();
        String contentHash = contentHash(new HashPayload(
                SCHEMA_VERSION, properties.applicationVersion(), runtimeMode,
                status, passed, warnings, blockers, checks));
        return new EnterpriseReadiness.Assessment(
                SCHEMA_VERSION, properties.applicationVersion(), runtimeMode, status,
                passed, warnings, blockers, checks, contentHash, generatedAt,
                generatedAt.plus(properties.snapshotValidity()));
    }

    public EnterpriseReadiness.Snapshot createSnapshot(String purpose) {
        String normalizedPurpose = purpose == null ? "" : purpose.trim();
        if (normalizedPurpose.isEmpty() || normalizedPurpose.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "就绪快照用途必须为 1 到 200 个字符");
        }
        EnterpriseReadiness.Assessment assessment = assess();
        return snapshotRepository.save(new EnterpriseReadiness.SnapshotDraft(
                UUID.randomUUID(), normalizedPurpose, assessment,
                actorProvider.currentActor().actorId()));
    }

    public PageResponse<EnterpriseReadiness.Snapshot> history(int page, int size) {
        return PageResponse.of(snapshotRepository.findAll(page, size), page, size,
                snapshotRepository.count());
    }

    private EnterpriseReadiness.Check toCheck(CheckDefinition definition, long affectedCount) {
        EnterpriseReadiness.CheckStatus status = affectedCount > 0
                ? definition.failureStatus()
                : EnterpriseReadiness.CheckStatus.PASS;
        return new EnterpriseReadiness.Check(
                definition.checkId(), definition.module(), status, affectedCount,
                threshold(definition.window()), definition.actionPath());
    }

    private void validateProbeResult(Map<String, Long> counts) {
        Set<String> expected = DEFINITIONS.stream()
                .map(CheckDefinition::checkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (counts == null || !counts.keySet().equals(expected)
                || counts.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalStateException("企业运行就绪检查结果不完整");
        }
    }

    private String threshold(Window window) {
        return switch (window) {
            case STALE -> properties.staleOperationAfter().toString();
            case REVIEW -> properties.reviewBacklogAfter().toString();
            case FAILURE -> properties.failedRunLookback().toString();
            case NONE -> null;
        };
    }

    private String contentHash(HashPayload payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JacksonException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("企业运行就绪证据哈希生成失败", ex);
        }
    }

    private static CheckDefinition blocker(
            String checkId, EnterpriseReadiness.Module module, String actionPath, Window window) {
        return new CheckDefinition(checkId, module,
                EnterpriseReadiness.CheckStatus.BLOCKER, actionPath, window);
    }

    private static CheckDefinition warning(
            String checkId, EnterpriseReadiness.Module module, String actionPath, Window window) {
        return new CheckDefinition(checkId, module,
                EnterpriseReadiness.CheckStatus.WARNING, actionPath, window);
    }

    private enum Window { NONE, STALE, REVIEW, FAILURE }

    private record CheckDefinition(
            String checkId,
            EnterpriseReadiness.Module module,
            EnterpriseReadiness.CheckStatus failureStatus,
            String actionPath,
            Window window) {
    }

    private record HashPayload(
            int schemaVersion,
            String applicationVersion,
            String runtimeMode,
            EnterpriseReadiness.OverallStatus status,
            int passedCount,
            int warningCount,
            int blockerCount,
            List<EnterpriseReadiness.Check> checks) {
    }
}
