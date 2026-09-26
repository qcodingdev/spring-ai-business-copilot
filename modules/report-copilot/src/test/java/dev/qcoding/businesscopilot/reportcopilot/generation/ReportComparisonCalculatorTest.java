package dev.qcoding.businesscopilot.reportcopilot.generation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ReportComparisonCalculatorTest {

    private final ReportComparisonCalculator calculator = new ReportComparisonCalculator();

    @Test
    void computesDeterministicChangeWithRecordedInputsAndFormula() {
        var result = calculator.compute(new BigDecimal("1200"), new BigDecimal("1000"),
                "percent", "2026-07", "2026-06");

        assertThat(result.computable()).isTrue();
        assertThat(result.changePercent().toPlainString()).isEqualTo("20.00");
        assertThat(result.absoluteDelta().toPlainString()).isEqualTo("200");
        assertThat(result.formula()).isEqualTo("(current - previous) / previous * 100");
        assertThat(result.currentValue().toPlainString()).isEqualTo("1200");
        assertThat(result.previousValue().toPlainString()).isEqualTo("1000");
        assertThat(result.needsReview()).isTrue();
    }

    @Test
    void flagsZeroDenominatorAsNotComputableInsteadOfGuessing() {
        // REP-02：零分母不可计算，明确标记而不是输出误导性数值。
        var result = calculator.compute(new BigDecimal("500"), BigDecimal.ZERO,
                "percent", "2026-07", "2026-06");

        assertThat(result.computable()).isFalse();
        assertThat(result.changePercent()).isNull();
        assertThat(result.notComputableReason()).contains("0");
        assertThat(result.needsReview()).isTrue();
    }

    @Test
    void negativeChangeBelowThresholdNeedsNoReview() {
        var result = calculator.compute(new BigDecimal("950"), new BigDecimal("1000"),
                "percent", "2026-07", "2026-06");

        assertThat(result.changePercent().toPlainString()).isEqualTo("-5.00");
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    void missingInputsAreNotComputable() {
        var result = calculator.compute(null, new BigDecimal("10"), "percent", "a", "b");
        assertThat(result.computable()).isFalse();
    }
}
