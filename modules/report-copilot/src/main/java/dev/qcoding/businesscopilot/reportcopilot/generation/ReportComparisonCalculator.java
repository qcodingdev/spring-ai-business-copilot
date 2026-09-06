package dev.qcoding.businesscopilot.reportcopilot.generation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * REP-02：可核验的确定性同比/环比计算器。
 *
 * <p>计算只由确定性代码完成：保存输入值、公式、周期与单位，不经过模型。
 * 零分母、缺输入或不可计算时返回明确标记，不产出误导性数值。</p>
 */
public class ReportComparisonCalculator {

    /** 变化率 = (当前值 - 对比值) / 对比值 × 100，保留两位小数（HALF_UP）。 */
    public static final String FORMULA_MOM_PERCENT = "(current - previous) / previous * 100";

    public ComparisonResult compute(BigDecimal currentValue, BigDecimal previousValue,
                                    String unit, String currentPeriod, String previousPeriod) {
        if (currentValue == null || previousValue == null) {
            return ComparisonResult.notComputable(unit, currentPeriod, previousPeriod,
                    "缺少当前期或对比期数值，无法计算变化率");
        }
        if (previousValue.compareTo(BigDecimal.ZERO) == 0) {
            return ComparisonResult.notComputable(unit, currentPeriod, previousPeriod,
                    "对比期数值为 0，百分比变化率不可计算；请改用绝对差值口径");
        }
        BigDecimal delta = currentValue.subtract(previousValue);
        BigDecimal changePercent = delta
                .divide(previousValue, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        boolean needsReview = changePercent.abs().compareTo(BigDecimal.valueOf(20)) >= 0;
        return new ComparisonResult(true, FORMULA_MOM_PERCENT,
                currentValue, previousValue, currentPeriod, previousPeriod, unit,
                delta, changePercent, needsReview, null);
    }

    /**
     * 计算结果。needsReview=true 表示 |变化率|≥20%，草稿必须进入人工复核，
     * 不能把剧烈变化当作正常结论直接交付。
     */
    public record ComparisonResult(boolean computable, String formula,
                                   BigDecimal currentValue, BigDecimal previousValue,
                                   String currentPeriod, String previousPeriod,
                                   String unit,
                                   BigDecimal absoluteDelta, BigDecimal changePercent,
                                   boolean needsReview, String notComputableReason) {

        public static ComparisonResult notComputable(String unit, String currentPeriod,
                                                     String previousPeriod, String reason) {
            return new ComparisonResult(false, FORMULA_MOM_PERCENT, null, null,
                    currentPeriod, previousPeriod, unit, null, null, true, reason);
        }
    }
}
