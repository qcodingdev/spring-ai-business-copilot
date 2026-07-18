package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rejects SQL that references any table outside the configured whitelist.
 *
 * <p>表白名单校验。从 AST 中递归提取所有被引用的表名，任何不在白名单中的表都拒绝。
 * 审计表 query_audit_logs 不在默认白名单，因此自然语言查询无法访问它。</p>
 */
public class SchemaWhitelistValidator implements SqlValidator {

    private final Set<String> whitelist;

    public SchemaWhitelistValidator(List<String> whitelist) {
        this.whitelist = normalize(whitelist);
    }

    @Override
    public String name() {
        return "SchemaWhitelist";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        if (!(context.parsedStatement() instanceof Select select)) {
            return;
        }
        Set<String> tables = new CanonicalTableNamesFinder().getTables((Statement) select);
        for (String table : tables) {
            if (!whitelist.contains(table)) {
                violations.add(SqlViolation.of(SqlViolationCode.TABLE_NOT_WHITELISTED, name(), table));
            }
        }
    }

    private Set<String> normalize(List<String> whitelist) {
        Set<String> normalized = new LinkedHashSet<>();
        if (whitelist == null) {
            return normalized;
        }
        for (String t : whitelist) {
            if (t != null && !t.isBlank()) {
                normalized.add(SqlIdentifierCanonicalizer.qualifiedName(t.trim()));
            }
        }
        return normalized;
    }

    /**
     * TablesNamesFinder already traverses CTEs, joins, nested FROM items and expression
     * subqueries. Keeping the fully-qualified name here prevents private.orders from
     * being reduced to orders and matching a same-name public table.
     */
    private static final class CanonicalTableNamesFinder extends TablesNamesFinder {

        @Override
        protected String extractTableName(Table table) {
            List<String> parts = table.getNameParts();
            StringBuilder canonical = new StringBuilder();
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (!canonical.isEmpty()) {
                    canonical.append('.');
                }
                canonical.append(SqlIdentifierCanonicalizer.identifier(parts.get(i)));
            }
            return canonical.toString();
        }
    }
}
