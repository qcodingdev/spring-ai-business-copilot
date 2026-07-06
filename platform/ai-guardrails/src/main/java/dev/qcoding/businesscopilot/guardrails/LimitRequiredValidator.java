package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.List;

/**
 * Enforces that non-aggregate queries include a LIMIT clause and that the limit
 * does not exceed the configured maximum.
 *
 * <p>非聚合查询默认必须有 LIMIT，限制单次查询最大行数，防止大结果集拖垮系统。</p>
 */
public class LimitRequiredValidator implements SqlValidator {

    private final int maxRows;
    private final boolean requireLimit;

    public LimitRequiredValidator(int maxRows, boolean requireLimit) {
        this.maxRows = maxRows;
        this.requireLimit = requireLimit;
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
            // Check limit on the outer set operation
            if (setOp.getLimit() != null) {
                checkLimitValue(setOp.getLimit(), violations);
            }
        }
        // Check limit on the outer Select (common for all subtypes)
        if (select.getLimit() != null) {
            checkLimitValue(select.getLimit(), violations);
        }
    }

    private void validatePlainSelect(PlainSelect plain, List<SqlViolation> violations) {
        boolean isAggregate = isAggregateQuery(plain);
        // Check the limit on the PlainSelect itself (in JSQLParser 4.9, Limit is on Select)
        // PlainSelect inherits from Select, so getLimit() is available
        if (plain.getLimit() != null) {
            checkLimitValue(plain.getLimit(), violations);
        } else if (requireLimit && !isAggregate) {
            // 非聚合查询缺少 LIMIT：强制要求添加
            violations.add(SqlViolation.of(SqlViolationCode.LIMIT_REQUIRED, name()));
        }
    }

    private void checkLimitValue(net.sf.jsqlparser.statement.select.Limit limit, List<SqlViolation> violations) {
        Expression rowCount = limit.getRowCount();
        if (rowCount instanceof LongValue lval) {
            long value = lval.getValue();
            if (value > maxRows) {
                violations.add(SqlViolation.of(SqlViolationCode.LIMIT_EXCEEDS_MAX, name(),
                        "limit " + value + " exceeds max " + maxRows));
            }
        }
        // Non-constant LIMIT expressions (e.g. parameters) are allowed through
    }

    /** Heuristic: query is aggregate if all select items are aggregate functions. */
    private boolean isAggregateQuery(PlainSelect plain) {
        if (plain.getGroupBy() != null) return true;
        List<SelectItem<?>> items = plain.getSelectItems();
        if (items == null || items.isEmpty()) return false;
        // If every expression item is a function, treat as aggregate
        for (SelectItem<?> item : items) {
            Expression expr = item.getExpression();
            if (!(expr instanceof Function)) {
                return false;
            }
        }
        return true;
    }
}
