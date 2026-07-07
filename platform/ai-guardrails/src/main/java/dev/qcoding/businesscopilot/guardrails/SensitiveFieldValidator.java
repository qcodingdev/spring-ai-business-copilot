package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.List;

/**
 * Rejects SQL that directly selects a blocked high-sensitivity column.
 *
 * <p>高敏字段阻断。password/token/secret/id_card 等字段禁止直接查询。
 * 注意：SELECT * 不会被这里完全拦截（无法静态判断列），但执行层 schema 标记和脱敏器
 * 会兜底处理；这里主要负责显式列名的拦截。</p>
 */
public class SensitiveFieldValidator implements SqlValidator {

    private final SensitiveFieldPolicy policy;

    public SensitiveFieldValidator(SensitiveFieldPolicy policy) {
        this.policy = policy;
    }

    @Override
    public String name() {
        return "SensitiveField";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        if (!(context.parsedStatement() instanceof Select select)) return;
        collectBlockedColumns(select, violations);
    }

    private void collectBlockedColumns(Select select, List<SqlViolation> violations) {
        if (select instanceof PlainSelect plain) {
            checkItems(plain.getSelectItems(), violations);
        } else if (select instanceof SetOperationList setOp) {
            if (setOp.getSelects() != null) {
                for (var branch : setOp.getSelects()) {
                    collectBlockedColumns(branch, violations);
                }
            }
        }
    }

    private void checkItems(List<SelectItem<?>> items, List<SqlViolation> violations) {
        if (items == null) return;
        for (SelectItem<?> item : items) {
            Expression expr = item.getExpression();
            collectBlockedColumns(expr, violations);
            // SELECT * cannot statically determine columns; rely on execution-time masking.
        }
    }

    private void collectBlockedColumns(Expression expr, List<SqlViolation> violations) {
        if (expr == null) return;
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                String name = column.getColumnName();
                if (policy.isBlocked(name)) {
                    violations.add(SqlViolation.of(SqlViolationCode.SENSITIVE_FIELD_BLOCKED, name(), name));
                }
            }
        });
    }
}
