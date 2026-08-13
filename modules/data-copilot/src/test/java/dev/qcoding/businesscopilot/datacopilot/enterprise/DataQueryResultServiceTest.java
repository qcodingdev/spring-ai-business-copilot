package dev.qcoding.businesscopilot.datacopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataQueryResultServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void hidesExpiredSnapshotsAndPurgesTheirPayloads() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                contains("expires_at > now()"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq("operator-1"), eq(20), eq(0))).thenReturn(List.of());
        when(jdbcTemplate.update(
                "DELETE FROM data_query_results WHERE expires_at <= now()"))
                .thenReturn(3);
        DataQueryResultService service = new DataQueryResultService(
                jdbcTemplate, new ObjectMapper(),
                () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
                Duration.ofHours(24));

        assertThat(service.listOwned(0, 20)).isEmpty();
        assertThat(service.purgeExpiredResults()).isEqualTo(3);

        verify(jdbcTemplate).query(
                contains("expires_at > now()"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq("operator-1"), eq(20), eq(0));
        verify(jdbcTemplate).update(
                "DELETE FROM data_query_results WHERE expires_at <= now()");
    }
}
