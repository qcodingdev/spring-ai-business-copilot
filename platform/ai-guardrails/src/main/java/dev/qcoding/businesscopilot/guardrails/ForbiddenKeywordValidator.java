package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.grant.Grant;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.execute.Execute;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects SQL whose AST or comment body reveals forbidden write/DDL keywords.
 *
 * <p>禁止关键字校验。结合 AST 语句类型判断和注释/文本正则匹配，防止：
 * <ul>
 *   <li>insert/update/delete/drop/alter/truncate/create/grant/revoke/merge/call/execute 语句类型；</li>
 *   <li>SQL 注释中夹带危险关键字（如 {@code -- drop table}）。</li>
 * </ul>
 * 不能只靠字符串 contains，但作为 AST 之外的双重防御，注释匹配仍然必要。</p>
 */
public class ForbiddenKeywordValidator implements SqlValidator {

    /** Forbidden statement keywords checked in the raw SQL text and comments. */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "insert", "update", "delete", "drop", "alter", "truncate",
            "create", "grant", "revoke", "merge", "call", "execute");

    /** Word-boundary regex that catches forbidden keywords even inside comments. */
    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|call|execute)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "ForbiddenKeyword";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        // 1. AST-level check: even if it parsed, reject known write/DDL statement types
        Statement stmt = context.parsedStatement();
        if (stmt != null) {
            String forbiddenType = detectForbiddenStatementType(stmt);
            if (forbiddenType != null) {
                violations.add(SqlViolation.of(SqlViolationCode.FORBIDDEN_KEYWORD, name(),
                        "statement type " + forbiddenType));
            }
        }
        // 2. Comment/text-level check: catch dangerous keywords smuggled in comments,
        //    which would be invisible to AST type checks on a SELECT wrapper.
        Matcher matcher = FORBIDDEN_PATTERN.matcher(context.normalizedSql());
        if (matcher.find()) {
            violations.add(SqlViolation.of(SqlViolationCode.FORBIDDEN_KEYWORD, name(),
                    "keyword '" + matcher.group(1).toLowerCase() + "'"));
        }
    }

    /** Map known write/DDL AST types to a forbidden label, or {@code null}. */
    private String detectForbiddenStatementType(Statement stmt) {
        if (stmt instanceof Insert) return "insert";
        if (stmt instanceof Update) return "update";
        if (stmt instanceof Delete) return "delete";
        if (stmt instanceof Drop) return "drop";
        if (stmt instanceof Alter) return "alter";
        if (stmt instanceof Truncate) return "truncate";
        if (stmt instanceof CreateTable) return "create";
        if (stmt instanceof Grant) return "grant";
        if (stmt instanceof Merge) return "merge";
        if (stmt instanceof Execute) return "execute";
        return null;
    }
}
