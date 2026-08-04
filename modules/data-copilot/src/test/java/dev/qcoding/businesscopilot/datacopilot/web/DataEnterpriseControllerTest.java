package dev.qcoding.businesscopilot.datacopilot.web;

import dev.qcoding.businesscopilot.datacopilot.enterprise.DataGovernanceService;
import dev.qcoding.businesscopilot.datacopilot.enterprise.DataQueryResultService;
import dev.qcoding.businesscopilot.datacopilot.query.QueryExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataEnterpriseControllerTest {

    private DataQueryResultService resultService;
    private DataEnterpriseController controller;

    @BeforeEach
    void setUp() {
        resultService = mock(DataQueryResultService.class);
        controller = new DataEnterpriseController(
                mock(DataGovernanceService.class), resultService, mock(QueryExecutionService.class));
    }

    @Test
    void returnsOwnedResultSnapshotsForExecutionRecords() {
        when(resultService.listOwned(0, 20)).thenReturn(List.of(
                new DataQueryResultService.ResultSummary(
                        7L, "candidate-7", 4, false,
                        Instant.parse("2026-08-04T00:00:00Z"),
                        Instant.parse("2026-08-03T00:00:00Z"))));

        var response = controller.results(0, 20);

        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).asList().hasSize(1);
    }

    @Test
    void returnsHandoffStatusForResultHandoffTab() {
        when(resultService.listHandoffs(0, 20)).thenReturn(List.of(
                new DataQueryResultService.HandoffSummary(
                        9L, 7L, "月度经营结果", "READY", "data-result-7", 4,
                        Instant.parse("2026-08-04T00:00:00Z"), null,
                        Instant.parse("2026-08-03T00:00:00Z"))));

        var response = controller.handoffs(0, 20);

        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).asList().singleElement()
                .extracting("status").isEqualTo("READY");
    }
}
