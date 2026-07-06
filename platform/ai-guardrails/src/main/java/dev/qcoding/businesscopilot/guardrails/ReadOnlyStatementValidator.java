package dev.qcoding.businesscopilot.guardrails;

import java.util.List;

/**
 * Rejects SQL when the JSQLParser fails to produce an AST.
 *
 * <p>Parser 失败默认拒绝。Parser 失败意味着 SQL 语法异常或包含可疑结构，
 * 不允许执行以防止绕过校验链。</p>
 */
public class ReadOnlyStatementValidator implements SqlValidator {

    @Override
    public String name() {
        return "ReadOnlyStatement";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        if (context.parseException() != null || !context.isParsed()) {
            // Parser 失败拒绝：语法异常或可疑结构可能绕过后续校验
            violations.add(SqlViolation.of(SqlViolationCode.UNPARSEABLE, name()));
            return;
        }
        if (!context.isSelectStatement()) {
            // 只允许 SELECT 或 WITH...SELECT，其他语句类型一律拒绝
            violations.add(SqlViolation.of(SqlViolationCode.NOT_READ_ONLY, name(),
                    context.parsedStatement().getClass().getSimpleName()));
        }
    }
}
