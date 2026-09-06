package dev.qcoding.businesscopilot.governance;

import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptGovernanceServiceTest {

    @Test
    void appendsAChineseSystemVersionWithoutRewritingTheOriginalSeed() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO prompt_definitions"), eq(Long.class),
                any(), any(), any(), any())).thenReturn(41L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(*) FROM prompt_versions"), eq(Integer.class), eq(41L)))
                .thenReturn(1);
        PromptGovernanceService service = new PromptGovernanceService(
                jdbcTemplate, mock(CurrentActorProvider.class));
        ClassPathResource resource = new ClassPathResource(
                "prompts/data-copilot/result-explanation.st");
        String bundledContent;
        try (var input = resource.getInputStream()) {
            bundledContent = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }

        ReflectionTestUtils.invokeMethod(service, "seed", resource);

        verify(jdbcTemplate).query(contains("WITH candidate AS"),
                org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
                eq(41L), eq(bundledContent), anyString(), anyString(), eq(41L));
        verify(jdbcTemplate, never()).update(contains("SET content ="),
                any(), any(), any());
    }

    @Test
    void doesNotAutomaticallyChangeAPromptThatAlreadyHasGovernedVersions() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO prompt_definitions"), eq(Long.class),
                any(), any(), any(), any())).thenReturn(42L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(*) FROM prompt_versions"), eq(Integer.class), eq(42L)))
                .thenReturn(2);
        PromptGovernanceService service = new PromptGovernanceService(
                jdbcTemplate, mock(CurrentActorProvider.class));

        ReflectionTestUtils.invokeMethod(service, "seed", new ClassPathResource(
                "prompts/data-copilot/result-explanation.st"));

        verify(jdbcTemplate, never()).query(contains("WITH candidate AS"),
                org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
                any(), any(), any(), any(), any());
    }
}
