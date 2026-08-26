package dev.qcoding.businesscopilot.readiness;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import dev.qcoding.businesscopilot.commonweb.api.PageResponse;
import dev.qcoding.businesscopilot.demo.RuntimeModeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Calculates live readiness and creates server-side, retention-bounded append-only evidence. */
@Service
public class EnterpriseReadinessService {

    private static final int SCHEMA_VERSION = 2;
    private static final List<CheckDefinition> PREREQUISITES = List.of(
            prerequisite("CHAT_MODEL_NOT_CONFIGURED", EnterpriseReadiness.Module.PLATFORM,
                    "/admin?tab=overview"),
            prerequisite("EMBEDDING_MODEL_NOT_CONFIGURED", EnterpriseReadiness.Module.PLATFORM,
                    "/admin?tab=overview"),
            prerequisite("DATA_MODULE_DISABLED", EnterpriseReadiness.Module.DATA, "/data"),
            prerequisite("KNOWLEDGE_MODULE_DISABLED", EnterpriseReadiness.Module.KNOWLEDGE,
                    "/knowledge"),
            prerequisite("SUPPORT_MODULE_DISABLED", EnterpriseReadiness.Module.SUPPORT,
                    "/support"),
            prerequisite("REPORT_MODULE_DISABLED", EnterpriseReadiness.Module.REPORT,
                    "/report"),
            prerequisite("HR_MODULE_DISABLED", EnterpriseReadiness.Module.HR, "/hr"));
    private static final List<CheckDefinition> OPERATIONAL_CHECKS = List.of(
            blocker("DATA_STALE_HANDOFF_CLAIMS", EnterpriseReadiness.Module.DATA,
                    "/data?tab=handoff", Window.STALE),
            warning("DATA_EXPIRED_RESULTS", EnterpriseReadiness.Module.DATA,
                    "/data?tab=records", Window.EXPIRED_RESULT),
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
                    "/report?tab=records", Window.NONE),
            warning("HR_OVERDUE_ASSESSMENT_REVIEWS", EnterpriseReadiness.Module.HR,
                    "/hr?section=recruiting&tab=assessment", Window.NONE),
            warning("HR_OVERDUE_ONBOARDING_TASKS", EnterpriseReadiness.Module.HR,
                    "/hr?section=employee&tab=onboarding", Window.NONE));
    private static final List<CheckDefinition> DEFINITIONS = Stream.concat(
            PREREQUISITES.stream(), OPERATIONAL_CHECKS.stream()).toList();

    private final EnterpriseReadinessProbeRepository probeRepository;
    private final EnterpriseReadinessSnapshotRepository snapshotRepository;
    private final EnterpriseReadinessProperties properties;
    private final RuntimeModeProperties runtimeModeProperties;
    private final Environment environment;
    private final CurrentActorProvider actorProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public EnterpriseReadinessService(
            EnterpriseReadinessProbeRepository probeRepository,
            EnterpriseReadinessSnapshotRepository snapshotRepository,
            EnterpriseReadinessProperties properties,
            RuntimeModeProperties runtimeModeProperties,
            Environment environment,
            CurrentActorProvider actorProvider,
            ObjectMapper objectMapper) {
        this(probeRepository, snapshotRepository, properties, runtimeModeProperties,
                environment, actorProvider, objectMapper, Clock.systemUTC());
    }

    EnterpriseReadinessService(
            EnterpriseReadinessProbeRepository probeRepository,
            EnterpriseReadinessSnapshotRepository snapshotRepository,
            EnterpriseReadinessProperties properties,
            RuntimeModeProperties runtimeModeProperties,
            Environment environment,
            CurrentActorProvider actorProvider,
            ObjectMapper objectMapper,
            Clock clock) {
        this.probeRepository = probeRepository;
        this.snapshotRepository = snapshotRepository;
        this.properties = properties;
        this.runtimeModeProperties = runtimeModeProperties;
        this.environment = environment;
        this.actorProvider = actorProvider;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public EnterpriseReadiness.Assessment assess() {
        Instant generatedAt = clock.instant();
        Map<String, Long> operationalCounts = probeRepository.probe(generatedAt, properties);
        validateProbeResult(operationalCounts);
        Map<String, Long> counts = new LinkedHashMap<>(prerequisiteCounts());
        counts.putAll(operationalCounts);
        List<EnterpriseReadiness.Check> checks = DEFINITIONS.stream()
                .map(definition -> toCheck(definition, counts.getOrDefault(definition.checkId(), 0L)))
                .toList();
        int blockers = Math.toIntExact(checks.stream()
                .filter(check -> check.status() == EnterpriseReadiness.CheckStatus.BLOCKER).count());
        int warnings = Math.toIntExact(checks.stream()
                .filter(check -> check.status() == EnterpriseReadiness.CheckStatus.WARNING).count());
        int passed = checks.size() - blockers - warnings;
        boolean configurationMissing = PREREQUISITES.stream()
                .anyMatch(definition -> counts.get(definition.checkId()) > 0);
        EnterpriseReadiness.OverallStatus status = configurationMissing
                ? EnterpriseReadiness.OverallStatus.NOT_CONFIGURED
                : blockers > 0
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
        UUID snapshotReference = UUID.randomUUID();
        String generatedBy = actorProvider.currentActor().actorId();
        String snapshotHash = contentHash(new SnapshotHashPayload(
                snapshotReference, normalizedPurpose, generatedBy,
                assessment.schemaVersion(), assessment.applicationVersion(),
                assessment.runtimeMode(), assessment.status(), assessment.passedCount(),
                assessment.warningCount(), assessment.blockerCount(), assessment.checks(),
                assessment.generatedAt(), assessment.validUntil()));
        EnterpriseReadiness.Assessment boundAssessment = new EnterpriseReadiness.Assessment(
                assessment.schemaVersion(), assessment.applicationVersion(), assessment.runtimeMode(),
                assessment.status(), assessment.passedCount(), assessment.warningCount(),
                assessment.blockerCount(), assessment.checks(), snapshotHash,
                assessment.generatedAt(), assessment.validUntil());
        return snapshotRepository.save(new EnterpriseReadiness.SnapshotDraft(
                snapshotReference, normalizedPurpose, boundAssessment, generatedBy));
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
        Set<String> expected = OPERATIONAL_CHECKS.stream()
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
            case EXPIRED_RESULT -> properties.expiredResultGrace().toString();
            case FAILURE -> properties.failedRunLookback().toString();
            case NONE -> null;
        };
    }

    private String contentHash(Object payload) {
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

    private static CheckDefinition prerequisite(
            String checkId, EnterpriseReadiness.Module module, String actionPath) {
        return new CheckDefinition(checkId, module,
                EnterpriseReadiness.CheckStatus.BLOCKER, actionPath, Window.NONE);
    }

    private Map<String, Long> prerequisiteCounts() {
        boolean modelDisabled = environment.getProperty(
                "business-copilot.ai-core.model-disabled", Boolean.class, false);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("CHAT_MODEL_NOT_CONFIGURED",
                missingModel("spring.ai.model.chat", modelDisabled));
        counts.put("EMBEDDING_MODEL_NOT_CONFIGURED",
                missingModel("spring.ai.model.embedding", modelDisabled));
        counts.put("DATA_MODULE_DISABLED", disabled("business-copilot.data-copilot.enabled", true));
        counts.put("KNOWLEDGE_MODULE_DISABLED", disabled("business-copilot.knowledge.enabled", true));
        counts.put("SUPPORT_MODULE_DISABLED", disabled("business-copilot.support-copilot.enabled", true));
        counts.put("REPORT_MODULE_DISABLED", disabled("business-copilot.report-copilot.enabled", true));
        counts.put("HR_MODULE_DISABLED", disabled("business-copilot.resume-copilot.enabled", true));
        return counts;
    }

    private long missingModel(String propertyName, boolean modelDisabled) {
        String value = environment.getProperty(propertyName, "none");
        return modelDisabled || value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())
                ? 1L : 0L;
    }

    private long disabled(String propertyName, boolean defaultValue) {
        return environment.getProperty(propertyName, Boolean.class, defaultValue) ? 0L : 1L;
    }

    private enum Window { NONE, STALE, EXPIRED_RESULT, FAILURE }

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

    private record SnapshotHashPayload(
            UUID snapshotReference,
            String purpose,
            String generatedBy,
            int schemaVersion,
            String applicationVersion,
            String runtimeMode,
            EnterpriseReadiness.OverallStatus status,
            int passedCount,
            int warningCount,
            int blockerCount,
            List<EnterpriseReadiness.Check> checks,
            Instant generatedAt,
            Instant validUntil) {
    }
}
