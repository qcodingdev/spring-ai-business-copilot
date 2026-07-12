package dev.qcoding.businesscopilot.resumecopilot.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumeRepositoryTest {

    @Test
    void auditFailureDoesNotFailCompletedBusinessAction() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("audit database unavailable"));
        ResumeRepository repository = new ResumeRepository(
                mock(ResumeJobMapper.class), mock(ResumeAssessmentMapper.class), jdbcTemplate);

        assertThatCode(() -> repository.audit("REVIEWED", 1L, 2L, 3L,
                4, 5, "model", "REVIEWED", null)).doesNotThrowAnyException();
    }
}
