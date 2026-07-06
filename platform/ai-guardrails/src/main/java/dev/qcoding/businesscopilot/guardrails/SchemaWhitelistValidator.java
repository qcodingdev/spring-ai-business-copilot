package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.List;

/**
 * Rejects SQL that references any table outside the configured whitelist.
 *
 * <p>表白名单校验。从 AST 中递归提取所有被引用的表名，任何不在白名单中的表都拒绝。
 * 审计表 query_audit_logs 不在默认白名单，因此自然语言查询无法访问它。</p>
 */
public class SchemaWhitelistValidator implements SqlValidator {

    private final List<String> whitelist;

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
        // Collect CTE aliases (WITH ... AS alias) — these are temporary tables
        // defined in the query itself and should be treated as whitelisted.
        List<String> cteAliases = new ArrayList<>();
        if (select.getWithItemsList() != null) {
            for (var withItem : select.getWithItemsList()) {
                // In JSQLParser 4.9 WithItem extends ParenthesedSelect;
                // the CTE name is stored in its Alias.
                if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
                    cteAliases.add(withItem.getAlias().getName().toLowerCase());
                }
            }
        }

        List<String> tables = new ArrayList<>();
        collectTables(select, tables);
        for (String table : tables) {
            String lower = table.toLowerCase();
            // CTE aliases are considered whitelisted for this query
            if (cteAliases.contains(lower)) continue;
            if (!whitelist.contains(lower)) {
                violations.add(SqlViolation.of(SqlViolationCode.TABLE_NOT_WHITELISTED, name(), table));
            }
        }
    }

    /** Recursively collect all table names referenced in a Select. */
    private void collectTables(Select select, List<String> tables) {
        if (select instanceof PlainSelect plain) {
            collectFromPlain(plain, tables);
        } else if (select instanceof SetOperationList setOp) {
            if (setOp.getSelects() != null) {
                for (Select branch : setOp.getSelects()) {
                    collectTables(branch, tables);
                }
            }
        }
        // Also check WITH items
        if (select.getWithItemsList() != null) {
            for (var withItem : select.getWithItemsList()) {
                if (withItem.getSelect() != null) {
                    collectTables(withItem.getSelect(), tables);
                }
            }
        }
    }

    private void collectFromPlain(PlainSelect plain, List<String> tables) {
        FromItem fromItem = plain.getFromItem();
        collectFromItem(fromItem, tables);
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                collectFromItem(join.getRightItem(), tables);
            }
        }
    }

    private void collectFromItem(FromItem fromItem, List<String> tables) {
        if (fromItem == null) return;
        if (fromItem instanceof Table table) {
            String name = table.getName();
            if (name != null) tables.add(name);
        } else if (fromItem instanceof ParenthesedSelect sub) {
            // Recurse into subqueries
            collectTables(sub.getSelect(), tables);
        }
    }

    private List<String> normalize(List<String> whitelist) {
        List<String> normalized = new ArrayList<>();
        for (String t : whitelist) {
            if (t != null && !t.isBlank()) normalized.add(t.trim().toLowerCase());
        }
        return normalized;
    }
}
