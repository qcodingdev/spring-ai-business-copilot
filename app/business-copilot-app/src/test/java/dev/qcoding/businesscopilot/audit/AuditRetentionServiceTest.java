package dev.qcoding.businesscopilot.audit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRetentionServiceTest {

    @Test
    void cleanupAnonymizesSensitiveDetailsBeforeDeletingExpiredMetadata() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(false);
        AuditRetentionService service = new AuditRetentionService(
                jdbcTemplate,
                new AuditRetentionProperties(Duration.ofDays(7), Duration.ofDays(30)));

        AuditRetentionService.CleanupResult result = service.cleanup();

        assertThat(result.anonymizedRows()).isEqualTo(5);
        assertThat(result.deletedRows()).isEqualTo(10);
        assertThat(jdbcTemplate.sql()).hasSize(10);
        assertThat(jdbcTemplate.sql().subList(0, 5))
                .allMatch(sql -> sql.startsWith("UPDATE "));
        assertThat(jdbcTemplate.sql().subList(5, 10))
                .allMatch(sql -> sql.startsWith("DELETE FROM "));
    }

    @Test
    void cleanupFailureIsContainedAndDoesNotPropagateIntoBusinessFlows() {
        AuditRetentionService service = new AuditRetentionService(
                new RecordingJdbcTemplate(true),
                new AuditRetentionProperties(Duration.ofDays(7), Duration.ofDays(30)));

        assertThat(service.cleanup())
                .isEqualTo(new AuditRetentionService.CleanupResult(0, 0));
    }

    @Test
    void invalidRetentionWindowsFallBackToBoundedDefaults() {
        AuditRetentionProperties properties =
                new AuditRetentionProperties(Duration.ZERO, Duration.ofDays(1));

        assertThat(properties.anonymizeAfter()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.deleteAfter()).isEqualTo(Duration.ofDays(30));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final boolean fail;
        private final List<String> sql = new ArrayList<>();

        private RecordingJdbcTemplate(boolean fail) {
            this.fail = fail;
        }

        @Override
        public int update(String sql, Object... args) {
            if (fail) {
                throw new IllegalStateException("audit database unavailable");
            }
            this.sql.add(sql);
            return sql.startsWith("UPDATE ") ? 1 : 2;
        }

        private List<String> sql() {
            return sql;
        }
    }
}
