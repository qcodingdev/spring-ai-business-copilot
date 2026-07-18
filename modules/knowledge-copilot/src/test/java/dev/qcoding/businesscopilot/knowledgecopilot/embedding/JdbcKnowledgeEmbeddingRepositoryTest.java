package dev.qcoding.businesscopilot.knowledgecopilot.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcKnowledgeEmbeddingRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void similaritySearchBindsThresholdBeforeLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(), any(), any(), any())).thenReturn(List.of());
        JdbcKnowledgeEmbeddingRepository repository = new JdbcKnowledgeEmbeddingRepository(jdbcTemplate);

        repository.findSimilarChunks(new float[]{0.1f, 0.2f}, "embedding-model", 5, 0.70d);

        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                eq("[0.1,0.2]"), eq("embedding-model"), eq(0.70d), eq(5));
    }
}
