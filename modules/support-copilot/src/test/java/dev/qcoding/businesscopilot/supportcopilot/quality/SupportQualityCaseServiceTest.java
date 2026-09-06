package dev.qcoding.businesscopilot.supportcopilot.quality;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportQualityCaseServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentActorProvider actorProvider = mock(CurrentActorProvider.class);
    private final SupportQualityCaseService service = new SupportQualityCaseService(
            jdbcTemplate, actorProvider, new SensitiveTextMasker());

    @BeforeEach
    void setUp() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(42L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(9L)))
                .thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("draft_id")).thenReturn(9L);
                    when(rs.getLong("ticket_id")).thenReturn(7L);
                    when(rs.getString("ticket_ref")).thenReturn("T-007");
                    when(rs.getString("draft_version")).thenReturn("actual-draft-v4");
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @Test
    void reviewerCanRecordCaseAndContentIsMasked() {
        when(actorProvider.currentActor()).thenReturn(
                new CurrentActor("reviewer-1", Set.of(BusinessRole.REVIEWER)));

        SupportQualityCaseService.QualityCase result = service.record(
                new SupportQualityCaseService.QualityCaseCommand(
                        "T-007", "RISKY_PROMISE",
                        "草稿承诺退款 500 元，客户手机号 13812345678",
                        "承诺未经授权", 9L, "kb-v3"));

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.createdBy()).isEqualTo("reviewer-1");
        assertThat(result.draftVersion()).isEqualTo("actual-draft-v4");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), args.capture());
        Object[] values = args.getValue();
        // SUP-05：客户原文必须脱敏入库。
        assertThat((String) values[2]).doesNotContain("13812345678");
    }

    @Test
    void operatorCannotRecordQualityCase() {
        when(actorProvider.currentActor()).thenReturn(
                new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)));

        assertThatThrownBy(() -> service.record(
                new SupportQualityCaseService.QualityCaseCommand(
                        "T-008", "NO_EVIDENCE", "案例", null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void listFiltersByCaseType() {
        when(actorProvider.currentActor()).thenReturn(
                new CurrentActor("admin-1", Set.of(BusinessRole.ADMIN)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("RISKY_PROMISE")))
                .thenReturn(List.of());

        assertThat(service.list("RISKY_PROMISE")).isEmpty();
    }
}
