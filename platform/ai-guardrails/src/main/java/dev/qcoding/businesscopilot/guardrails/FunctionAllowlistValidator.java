package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JsonAggregateFunction;
import net.sf.jsqlparser.expression.JsonFunction;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TranscodingFunction;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Denies database functions by default and only permits explicitly configured
 * unqualified aggregate functions.
 *
 * <p>数据库函数默认拒绝。允许列表只接受未限定的聚合函数名，避免通过
 * pg_catalog.count 等 schema 限定形式绕过策略，也会覆盖子查询和表函数。</p>
 */
public class FunctionAllowlistValidator implements SqlValidator {

    private final Set<String> allowedAggregateFunctions;

    public FunctionAllowlistValidator(List<String> allowedAggregateFunctions) {
        this.allowedAggregateFunctions = new LinkedHashSet<>();
        if (allowedAggregateFunctions != null) {
            for (String function : allowedAggregateFunctions) {
                if (function != null && !function.isBlank()) {
                    this.allowedAggregateFunctions.add(function.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    @Override
    public String name() {
        return "FunctionAllowlist";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        if (!(context.parsedStatement() instanceof Select select)) {
            return;
        }
        new FunctionFinder(violations).getTables((Statement) select);
    }

    private final class FunctionFinder extends TablesNamesFinder {

        private final List<SqlViolation> violations;

        private FunctionFinder(List<SqlViolation> violations) {
            this.violations = violations;
        }

        @Override
        public void visit(Function function) {
            List<String> multipartName = function.getMultipartName();
            String displayName = function.getName();
            boolean unqualified = multipartName != null && multipartName.size() == 1;
            String simpleName = unqualified
                    ? multipartName.getFirst().toLowerCase(Locale.ROOT)
                    : "";
            if (!unqualified || !allowedAggregateFunctions.contains(simpleName)) {
                reject(displayName);
            }
            super.visit(function);
        }

        @Override
        public void visit(AnalyticExpression expression) {
            reject(expression.toString());
            super.visit(expression);
        }

        @Override
        public void visit(CastExpression expression) {
            reject("cast");
            super.visit(expression);
        }

        @Override
        public void visit(ExtractExpression expression) {
            reject("extract");
            super.visit(expression);
        }

        @Override
        public void visit(TrimFunction expression) {
            reject("trim");
            super.visit(expression);
        }

        @Override
        public void visit(TranscodingFunction expression) {
            reject(expression.toString());
            super.visit(expression);
        }

        @Override
        public void visit(JsonFunction expression) {
            reject("json");
            super.visit(expression);
        }

        @Override
        public void visit(JsonAggregateFunction expression) {
            reject("json_aggregate");
            super.visit(expression);
        }

        @Override
        public void visit(MySQLGroupConcat expression) {
            reject("group_concat");
            super.visit(expression);
        }

        @Override
        public void visit(TimeKeyExpression expression) {
            reject(expression.toString());
            super.visit(expression);
        }

        @Override
        public void visit(NextValExpression expression) {
            reject("nextval");
            super.visit(expression);
        }

        private void reject(String functionName) {
            violations.add(SqlViolation.of(
                    SqlViolationCode.FUNCTION_NOT_ALLOWED,
                    name(),
                    functionName == null ? "unknown" : functionName));
        }
    }
}
