package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces that non-aggregate queries include a LIMIT clause and that the limit
 * does not exceed the configured maximum.
 *
 * <p>非聚合查询默认必须有 LIMIT，限制单次查询最大行数，防止大结果集拖垮系统。</p>
 */
public class LimitRequiredValidator implements SqlValidator {

    private final int maxRows;
    private final boolean requireLimit;
    private final Set<String> allowedAggregateFunctions;

    public LimitRequiredValidator(int maxRows, boolean requireLimit) {
        this(maxRows, requireLimit, List.of("count", "sum", "avg", "min", "max"));
    }

    public LimitRequiredValidator(int maxRows,
                                  boolean requireLimit,
                                  List<String> allowedAggregateFunctions) {
        this.maxRows = maxRows;
        this.requireLimit = requireLimit;
        this.allowedAggregateFunctions = allowedAggregateFunctions == null
                ? Set.of()
                : allowedAggregateFunctions.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> name.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String name() {
        return "LimitRequired";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        if (!(context.parsedStatement() instanceof Select select)) return;
        validateSelect(select, violations);
    }

    private void validateSelect(Select select, List<SqlViolation> violations) {
        if (select instanceof PlainSelect plain) {
            validatePlainSelect(plain, violations);
        } else if (select instanceof SetOperationList setOp) {
            if (setOp.getSelects() != null) {
                for (var branch : setOp.getSelects()) {
                    validateSelect(branch, violations);
                }
            }
            checkLimitValue(setOp.getLimit(), violations);
        }
    }

    private void validatePlainSelect(PlainSelect plain, List<SqlViolation> violations) {
        boolean isAggregate = isAggregateQuery(plain);
        if (plain.getLimit() != null) {
            checkLimitValue(plain.getLimit(), violations);
        } else if (requireLimit && !isAggregate) {
            // 非聚合查询缺少 LIMIT：强制要求添加
            violations.add(SqlViolation.of(SqlViolationCode.LIMIT_REQUIRED, name()));
        }
    }

    private void checkLimitValue(net.sf.jsqlparser.statement.select.Limit limit,
                                 List<SqlViolation> violations) {
        if (limit == null) {
            return;
        }
        Expression rowCount = limit.getRowCount();
        if (!(rowCount instanceof LongValue lval)) {
            violations.add(SqlViolation.of(
                    SqlViolationCode.LIMIT_NOT_BOUNDED_CONSTANT,
                    name(),
                    String.valueOf(rowCount)));
            return;
        }
        long value = lval.getValue();
        if (value < 0) {
            violations.add(SqlViolation.of(
                    SqlViolationCode.LIMIT_NOT_BOUNDED_CONSTANT,
                    name(),
                    "limit must be non-negative"));
        } else if (value > maxRows) {
            violations.add(SqlViolation.of(SqlViolationCode.LIMIT_EXCEEDS_MAX, name(),
                    "limit " + value + " exceeds max " + maxRows));
        }
    }

    /** Only a single-row, ungrouped query made entirely of allowed aggregates may omit LIMIT. */
    private boolean isAggregateQuery(PlainSelect plain) {
        if (plain.getGroupBy() != null) return false;
        List<SelectItem<?>> items = plain.getSelectItems();
        if (items == null || items.isEmpty()) return false;
        for (SelectItem<?> item : items) {
            Expression expr = item.getExpression();
            if (!(expr instanceof Function function)
                    || function.getMultipartName() == null
                    || function.getMultipartName().size() != 1
                    || !allowedAggregateFunctions.contains(
                            function.getMultipartName().getFirst().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
