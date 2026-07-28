package dev.qcoding.businesscopilot.datacopilot.enterprise;

import dev.qcoding.businesscopilot.commonsecurity.BusinessRole;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.datacopilot.confirmation.SqlConfirmationService;
import dev.qcoding.businesscopilot.datacopilot.schema.BusinessDatabaseDialect;
import dev.qcoding.businesscopilot.datacopilot.schema.SchemaContextService;
import dev.qcoding.businesscopilot.guardrails.GuardrailsProperties;
import dev.qcoding.businesscopilot.guardrails.SqlGuardrailService;
import dev.qcoding.businesscopilot.guardrails.SqlValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataGovernanceServiceTest {

    private final JdbcTemplate platformJdbc = mock(JdbcTemplate.class);
    private final JdbcTemplate businessJdbc = mock(JdbcTemplate.class);
    private final SchemaContextService schemaContextService = mock(SchemaContextService.class);
    private final SqlGuardrailService guardrailService = mock(SqlGuardrailService.class);
    private final SqlConfirmationService confirmationService = mock(SqlConfirmationService.class);
    private final GuardrailsProperties guardrails = new GuardrailsProperties(
            null, null, null, 100, true);
    private DataGovernanceService service;

    @BeforeEach
    void setUp() {
        when(guardrailService.validate(anyString(), same(guardrails)))
                .thenAnswer(invocation -> SqlValidationResult.pass(invocation.getArgument(0)));
        service = new DataGovernanceService(
                platformJdbc, businessJdbc, BusinessDatabaseDialect.POSTGRESQL,
                schemaContextService, guardrailService, guardrails, confirmationService,
                () -> new CurrentActor("operator-1", Set.of(BusinessRole.OPERATOR)),
                new ObjectMapper(), new DataEnterpriseProperties(100_000, true));
    }

    @Test
    void blocksPostgresPlanThatExceedsRowBudgetEvenWhenPlanIsEscapedInJdbcResult() {
        when(businessJdbc.queryForList("EXPLAIN (FORMAT JSON) SELECT id FROM customers LIMIT 10"))
                .thenReturn(List.of(Map.of(
                        "QUERY PLAN",
                        "[{\"Plan\":{\"Node Type\":\"Index Scan\",\"Plan Rows\":250000}}]")));

        DataGovernanceService.CostPreview preview =
                service.previewCost("SELECT id FROM customers LIMIT 10");

        assertThat(preview.estimatedRows()).isEqualTo(250_000);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.rejectionReason()).isEqualTo("QUERY_BUDGET_EXCEEDED");
    }

    @Test
    void failsClosedWhenDatabasePlanDoesNotExposeRecognizableEstimatedRows() {
        when(businessJdbc.queryForList("EXPLAIN (FORMAT JSON) SELECT id FROM customers LIMIT 10"))
                .thenReturn(List.of(Map.of("QUERY PLAN", "[{\"Plan\":{\"Node Type\":\"Result\"}}]")));

        DataGovernanceService.CostPreview preview =
                service.previewCost("SELECT id FROM customers LIMIT 10");

        assertThat(preview.estimatedRows()).isEqualTo(Long.MAX_VALUE);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.rejectionReason()).isEqualTo("QUERY_BUDGET_EXCEEDED");
    }
}
