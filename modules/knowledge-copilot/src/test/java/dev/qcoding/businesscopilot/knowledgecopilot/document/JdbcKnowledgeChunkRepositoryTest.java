package dev.qcoding.businesscopilot.knowledgecopilot.document;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcKnowledgeChunkRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void textAndKeywordSearchRejectExpiredOrConflictedDocuments() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        JdbcKnowledgeChunkRepository repository = new JdbcKnowledgeChunkRepository(jdbcTemplate);

        repository.findByTextSearch("退款政策", 5);
        repository.findByKeywordSearch(List.of("退款", "政策"), 5);

        verify(jdbcTemplate, times(2)).query(
                argThat(JdbcKnowledgeChunkRepositoryTest::hasLifecycleGuard),
                any(RowMapper.class), any(Object[].class));
    }

    private static boolean hasLifecycleGuard(String sql) {
        return sql.contains("d.expires_at > now()")
                && sql.contains("d.conflict_status = 'NONE'");
    }
}
