package dev.qcoding.businesscopilot.readiness;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.PageResponse;
import dev.qcoding.businesscopilot.demo.RuntimeModeProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseReadinessServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T01:00:00Z");

    @Test
    void springSelectsTheProductionConstructor() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(EnterpriseReadinessProbeRepository.class,
                    () -> mock(EnterpriseReadinessProbeRepository.class));
            context.registerBean(EnterpriseReadinessSnapshotRepository.class,
                    () -> mock(EnterpriseReadinessSnapshotRepository.class));
            context.registerBean(EnterpriseReadinessProperties.class,
                    () -> new EnterpriseReadinessProperties(null, null, null, null, null, null));
            context.registerBean(RuntimeModeProperties.class,
                    () -> new RuntimeModeProperties("self-hosted"));
            context.registerBean(CurrentActorProvider.class,
                    () -> () -> new CurrentActor("admin-1", Set.of(BusinessRole.ADMIN)));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(EnterpriseReadinessService.class);

            context.refresh();

            assertThat(context.getBean(EnterpriseReadinessService.class)).isNotNull();
        }
    }

    @Test
    void blockerWinsAndStateHashIsStableForTheSameEvidence() {
        EnterpriseReadinessProbeRepository probes = mock(EnterpriseReadinessProbeRepository.class);
        Map<String, Long> counts = zeroCounts();
        counts.put("DATA_STALE_HANDOFF_CLAIMS", 2L);
        counts.put("REPORT_FAILED_RUNS", 3L);
        when(probes.probe(any(), any())).thenReturn(counts);
        EnterpriseReadinessService service = service(probes, mock(EnterpriseReadinessSnapshotRepository.class));

        EnterpriseReadiness.Assessment first = service.assess();
        EnterpriseReadiness.Assessment second = service.assess();

        assertThat(first.status()).isEqualTo(EnterpriseReadiness.OverallStatus.BLOCKED);
        assertThat(first.blockerCount()).isEqualTo(1);
        assertThat(first.warningCount()).isEqualTo(1);
        assertThat(first.passedCount()).isEqualTo(18);
        assertThat(first.checks()).hasSize(20);
        EnterpriseReadiness.Check staleHandoff = first.checks().stream()
                .filter(check -> check.checkId().equals("DATA_STALE_HANDOFF_CLAIMS"))
                .findFirst().orElseThrow();
        assertThat(staleHandoff.affectedCount()).isEqualTo(2);
        assertThat(staleHandoff.threshold()).isEqualTo("PT15M");
        assertThat(first.checks().stream()
                .filter(check -> check.checkId().equals("DATA_EXPIRED_RESULTS"))
                .findFirst().orElseThrow().threshold()).isEqualTo("PT1H");
        assertThat(first.contentHash()).hasSize(64).isEqualTo(second.contentHash());
        assertThat(first.generatedAt()).isEqualTo(NOW);
        assertThat(first.validUntil()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void snapshotIsRecalculatedServerSideAndBoundToTheCurrentActor() {
        EnterpriseReadinessProbeRepository probes = mock(EnterpriseReadinessProbeRepository.class);
        when(probes.probe(any(), any())).thenReturn(zeroCounts());
        EnterpriseReadinessSnapshotRepository snapshots = mock(EnterpriseReadinessSnapshotRepository.class);
        when(snapshots.save(any())).thenAnswer(invocation -> {
            EnterpriseReadiness.SnapshotDraft draft = invocation.getArgument(0);
            EnterpriseReadiness.Assessment assessment = draft.assessment();
            return new EnterpriseReadiness.Snapshot(
                    7L, draft.snapshotReference(), assessment.schemaVersion(), draft.purpose(),
                    assessment.applicationVersion(), assessment.runtimeMode(), assessment.status(),
                    assessment.passedCount(), assessment.warningCount(), assessment.blockerCount(),
                    assessment.checks(), assessment.contentHash(), draft.generatedBy(),
                    assessment.generatedAt(), assessment.validUntil());
        });
        EnterpriseReadinessService service = service(probes, snapshots);

        EnterpriseReadiness.Assessment liveAssessment = service.assess();
        EnterpriseReadiness.Snapshot snapshot = service.createSnapshot("  生产前复核  ");

        ArgumentCaptor<EnterpriseReadiness.SnapshotDraft> draft =
                ArgumentCaptor.forClass(EnterpriseReadiness.SnapshotDraft.class);
        verify(snapshots).save(draft.capture());
        assertThat(draft.getValue().purpose()).isEqualTo("生产前复核");
        assertThat(draft.getValue().generatedBy()).isEqualTo("admin-1");
        assertThat(snapshot.status()).isEqualTo(EnterpriseReadiness.OverallStatus.READY);
        assertThat(snapshot.passedCount()).isEqualTo(20);
        assertThat(snapshot.contentHash()).hasSize(64);
        assertThat(snapshot.contentHash()).isEqualTo(draft.getValue().assessment().contentHash());
        assertThat(snapshot.contentHash()).isNotEqualTo(liveAssessment.contentHash());
        verify(probes, times(2)).probe(any(), any());
    }

    @Test
    void missingModelsAndDisabledModulesCannotReportReady() {
        EnterpriseReadinessProbeRepository probes = mock(EnterpriseReadinessProbeRepository.class);
        when(probes.probe(any(), any())).thenReturn(zeroCounts());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "none")
                .withProperty("spring.ai.model.embedding", "none")
                .withProperty("business-copilot.support-copilot.enabled", "false");

        EnterpriseReadiness.Assessment assessment = service(
                probes, mock(EnterpriseReadinessSnapshotRepository.class), environment).assess();

        assertThat(assessment.status()).isEqualTo(EnterpriseReadiness.OverallStatus.NOT_CONFIGURED);
        assertThat(assessment.blockerCount()).isEqualTo(3);
        assertThat(assessment.checks()).filteredOn(check -> check.affectedCount() > 0)
                .extracting(EnterpriseReadiness.Check::checkId)
                .containsExactly("CHAT_MODEL_NOT_CONFIGURED", "EMBEDDING_MODEL_NOT_CONFIGURED",
                        "SUPPORT_MODULE_DISABLED");
    }

    @Test
    void incompleteProbeSetFailsClosedInsteadOfReportingReady() {
        EnterpriseReadinessProbeRepository probes = mock(EnterpriseReadinessProbeRepository.class);
        when(probes.probe(any(), any())).thenReturn(Map.of("DATA_EXPIRED_RESULTS", 0L));
        EnterpriseReadinessService service = service(
                probes, mock(EnterpriseReadinessSnapshotRepository.class));

        assertThatThrownBy(service::assess)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不完整");
    }

    @Test
    void purposeAndHistoryRemainBounded() {
        EnterpriseReadinessProbeRepository probes = mock(EnterpriseReadinessProbeRepository.class);
        EnterpriseReadinessSnapshotRepository snapshots = mock(EnterpriseReadinessSnapshotRepository.class);
        EnterpriseReadinessService service = service(probes, snapshots);
        when(snapshots.findAll(2, 10)).thenReturn(List.of());
        when(snapshots.count()).thenReturn(21L);

        assertThatThrownBy(() -> service.createSnapshot(" "))
                .hasMessageContaining("1 到 200");
        PageResponse<EnterpriseReadiness.Snapshot> page = service.history(2, 10);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void invalidWindowsFailFastInsteadOfChangingRetentionPolicy() {
        EnterpriseReadinessProperties defaults = new EnterpriseReadinessProperties(
                " ", null, null, null, null, null);

        assertThat(defaults.applicationVersion()).isEqualTo("2.4.0");
        assertThat(defaults.snapshotValidity()).isEqualTo(Duration.ofHours(24));
        assertThat(defaults.snapshotRetention()).isEqualTo(Duration.ofDays(90));
        assertThatThrownBy(() -> new EnterpriseReadinessProperties(
                "2.4.0", Duration.ZERO, Duration.ofDays(90),
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot-validity");
        assertThatThrownBy(() -> new EnterpriseReadinessProperties(
                "2.4.0", Duration.ofHours(24), Duration.ofHours(1),
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot-retention");
        assertThatThrownBy(() -> new EnterpriseReadinessProperties(
                "v".repeat(65), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    private EnterpriseReadinessService service(
            EnterpriseReadinessProbeRepository probes,
            EnterpriseReadinessSnapshotRepository snapshots) {
        return service(probes, snapshots, configuredEnvironment());
    }

    private EnterpriseReadinessService service(
            EnterpriseReadinessProbeRepository probes,
            EnterpriseReadinessSnapshotRepository snapshots,
            MockEnvironment environment) {
        EnterpriseReadinessProperties properties = new EnterpriseReadinessProperties(
                "2.4.0", Duration.ofHours(24), Duration.ofDays(90),
                Duration.ofMinutes(15), Duration.ofHours(1),
                Duration.ofDays(7));
        return new EnterpriseReadinessService(
                probes, snapshots, properties, new RuntimeModeProperties("self-hosted"),
                environment,
                () -> new CurrentActor("admin-1", Set.of(BusinessRole.ADMIN)),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MockEnvironment configuredEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.model.embedding", "openai")
                .withProperty("business-copilot.data-copilot.enabled", "true")
                .withProperty("business-copilot.knowledge.enabled", "true")
                .withProperty("business-copilot.support-copilot.enabled", "true")
                .withProperty("business-copilot.report-copilot.enabled", "true")
                .withProperty("business-copilot.resume-copilot.enabled", "true");
    }

    private Map<String, Long> zeroCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String checkId : List.of(
                "DATA_STALE_HANDOFF_CLAIMS", "DATA_EXPIRED_RESULTS",
                "KNOWLEDGE_STALE_SYNC_RUNS", "KNOWLEDGE_FAILED_SYNC_RUNS",
                "KNOWLEDGE_BLOCKED_DOCUMENTS", "SUPPORT_UNKNOWN_WRITEBACKS",
                "SUPPORT_STALE_WRITEBACKS", "SUPPORT_BREACHED_SLA",
                "REPORT_STALE_SCHEDULE_CLAIMS", "REPORT_FAILED_RUNS",
                "REPORT_OVERDUE_REVIEWS", "HR_OVERDUE_ASSESSMENT_REVIEWS",
                "HR_OVERDUE_ONBOARDING_TASKS")) {
            counts.put(checkId, 0L);
        }
        return counts;
    }
}
