package dev.qcoding.businesscopilot.readiness;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseReadinessRetentionServiceTest {

    @Test
    void cleanupDeletesOnlyEvidenceOutsideTheRetentionWindow() {
        EnterpriseReadinessSnapshotRepository repository =
                mock(EnterpriseReadinessSnapshotRepository.class);
        when(repository.deleteGeneratedBefore(argThat(cutoff ->
                cutoff.isBefore(Instant.now().minus(Duration.ofDays(89)))))).thenReturn(3);
        EnterpriseReadinessProperties properties = new EnterpriseReadinessProperties(
                "2.4.0", Duration.ofHours(24), Duration.ofDays(90),
                Duration.ofMinutes(15), Duration.ofHours(1),
                Duration.ofDays(7));
        EnterpriseReadinessRetentionService service =
                new EnterpriseReadinessRetentionService(repository, properties);

        assertThat(service.cleanup()).isEqualTo(3);
        verify(repository).deleteGeneratedBefore(anyInstantNearNinetyDaysAgo());
    }

    @Test
    void cleanupFailureDoesNotAffectBusinessFlows() {
        EnterpriseReadinessSnapshotRepository repository =
                mock(EnterpriseReadinessSnapshotRepository.class);
        when(repository.deleteGeneratedBefore(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        EnterpriseReadinessRetentionService service = new EnterpriseReadinessRetentionService(
                repository, new EnterpriseReadinessProperties(
                        null, null, null, null, null, null));

        assertThat(service.cleanup()).isZero();
    }

    private static Instant anyInstantNearNinetyDaysAgo() {
        return argThat(cutoff -> {
            Instant expected = Instant.now().minus(Duration.ofDays(90));
            return Duration.between(expected, cutoff).abs().compareTo(Duration.ofSeconds(5)) < 0;
        });
    }
}
