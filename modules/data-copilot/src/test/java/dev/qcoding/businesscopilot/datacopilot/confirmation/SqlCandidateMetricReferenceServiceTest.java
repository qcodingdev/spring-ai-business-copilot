package dev.qcoding.businesscopilot.datacopilot.confirmation;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlCandidateMetricReferenceServiceTest {

    @Test
    void acceptsOnlyTheSameActiveApprovedMetricVersion() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubReference(jdbcTemplate, "monthly_gmv", 4);
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), eq("monthly_gmv")))
                .thenReturn(List.of(4));

        assertThatCode(() -> new SqlCandidateMetricReferenceService(jdbcTemplate)
                .requireCurrent("candidate-1")).doesNotThrowAnyException();
    }

    @Test
    void rejectsCandidateWhenAdoptedMetricVersionChangedOrWasDisabled() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubReference(jdbcTemplate, "monthly_gmv", 4);
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), eq("monthly_gmv")))
                .thenReturn(List.of(5));

        assertThatThrownBy(() -> new SqlCandidateMetricReferenceService(jdbcTemplate)
                .requireCurrent("candidate-1")).isInstanceOf(BusinessException.class);
    }

    @SuppressWarnings("unchecked")
    private void stubReference(JdbcTemplate jdbcTemplate, String key, int version) throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("candidate-1")))
                .thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("metric_key")).thenReturn(key);
                    when(rs.getInt("metric_version")).thenReturn(version);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }
}
