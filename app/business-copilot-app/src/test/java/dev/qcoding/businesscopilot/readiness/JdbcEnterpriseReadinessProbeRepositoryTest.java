package dev.qcoding.businesscopilot.readiness;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcEnterpriseReadinessProbeRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void probesAllFiveModulesWithoutSelectingBusinessContent() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("knowledge_blocked_documents")).thenReturn(4L);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Map<String, Long>> mapper = invocation.getArgument(1);
                    return mapper.mapRow(resultSet, 0);
                });
        EnterpriseReadinessProperties properties = new EnterpriseReadinessProperties(
                "2.4.0", Duration.ofHours(24), Duration.ofDays(90),
                Duration.ofMinutes(15), Duration.ofHours(1),
                Duration.ofDays(7));
        JdbcEnterpriseReadinessProbeRepository repository =
                new JdbcEnterpriseReadinessProbeRepository(jdbcTemplate);

        Map<String, Long> counts = repository.probe(
                Instant.parse("2026-08-20T01:00:00Z"), properties);

        assertThat(counts).hasSize(13);
        assertThat(counts.get("KNOWLEDGE_BLOCKED_DOCUMENTS")).isEqualTo(4L);
        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("data_report_handoffs")
                        && sql.contains("knowledge_documents")
                        && sql.contains("support_draft_writebacks")
                        && sql.contains("report_schedule_runs")
                        && sql.contains("hr_onboarding_tasks")
                        && sql.contains("draft.review_due_at <= boundary.now_at")
                        && sql.contains("assessment.review_due_at <= boundary.now_at")
                        && sql.contains("task.due_at <= boundary.now_at")
                        && !sql.contains("review_before")
                        && sql.contains("NOT EXISTS")
                        && sql.contains("recovery.status = 'COMPLETED'")
                        && sql.contains("recovery.status IN ('DRAFTED', 'NEEDS_REVIEW')")
                        && !sql.contains("customer_message")
                        && !sql.contains("structured_content")
                        && !sql.contains("sanitized_resume")),
                any(RowMapper.class), any(Object[].class));
    }
}
