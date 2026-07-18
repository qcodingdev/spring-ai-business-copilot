package dev.qcoding.businesscopilot.guardrails;

import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rejects wildcard and explicit column references outside the configured table-column allowlist. */
public class ColumnWhitelistValidator implements SqlValidator {

    private final Map<String, Set<String>> allowedColumnsByTable;

    public ColumnWhitelistValidator(List<String> queryableColumns) {
        this.allowedColumnsByTable = normalize(queryableColumns);
    }

    @Override
    public String name() {
        return "ColumnWhitelist";
    }

    @Override
    public void validate(SqlValidationContext context, List<SqlViolation> violations) {
        if (!(context.parsedStatement() instanceof Select select)) {
            return;
        }
        ColumnReferenceFinder finder = new ColumnReferenceFinder(allowedColumnsByTable.keySet());
        finder.getTables((Statement) select);

        if (finder.wildcardSelected()) {
            violations.add(SqlViolation.of(
                    SqlViolationCode.COLUMN_NOT_WHITELISTED, name(), "*"));
        }
        for (Column column : finder.columns()) {
            if (!isAllowed(column, finder)) {
                violations.add(SqlViolation.of(
                        SqlViolationCode.COLUMN_NOT_WHITELISTED,
                        name(), column.getFullyQualifiedName()));
            }
        }
    }

    private boolean isAllowed(Column column, ColumnReferenceFinder finder) {
        String columnName = SqlIdentifierCanonicalizer.identifier(column.getColumnName());
        if (columnName.isBlank()) {
            return false;
        }
        String qualifier = column.getTable() != null
                ? SqlIdentifierCanonicalizer.qualifiedName(column.getTable().getFullyQualifiedName())
                : "";
        if (!qualifier.isBlank()) {
            String table = finder.resolveTable(qualifier);
            if (table != null) {
                return allowedColumnsByTable.getOrDefault(table, Set.of()).contains(columnName);
            }
        }

        List<String> matchingTables = finder.physicalTables().stream()
                .filter(table -> allowedColumnsByTable.getOrDefault(table, Set.of()).contains(columnName))
                .toList();
        return matchingTables.size() == 1;
    }

    private Map<String, Set<String>> normalize(List<String> queryableColumns) {
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        if (queryableColumns == null) {
            return normalized;
        }
        for (String configured : queryableColumns) {
            String canonical = SqlIdentifierCanonicalizer.qualifiedName(configured);
            int separator = canonical.lastIndexOf('.');
            if (separator <= 0 || separator == canonical.length() - 1) {
                continue;
            }
            String table = canonical.substring(0, separator);
            String column = canonical.substring(separator + 1);
            normalized.computeIfAbsent(table, ignored -> new LinkedHashSet<>()).add(column);
        }
        return normalized;
    }

    private static final class ColumnReferenceFinder extends TablesNamesFinder {

        private final Set<String> allowedTables;
        private final Set<String> physicalTables = new LinkedHashSet<>();
        private final Map<String, String> tableQualifiers = new LinkedHashMap<>();
        private final List<Column> columns = new ArrayList<>();
        private boolean wildcardSelected;

        private ColumnReferenceFinder(Set<String> allowedTables) {
            this.allowedTables = allowedTables;
        }

        @Override
        public void visit(Table table) {
            String canonical = canonicalTable(table);
            if (allowedTables.contains(canonical)) {
                physicalTables.add(canonical);
                registerQualifier(canonical, canonical);
                registerQualifier(SqlIdentifierCanonicalizer.simpleName(canonical), canonical);
                if (table.getAlias() != null) {
                    registerQualifier(
                            SqlIdentifierCanonicalizer.identifier(table.getAlias().getName()),
                            canonical);
                }
            }
            super.visit(table);
        }

        @Override
        public void visit(Column column) {
            columns.add(column);
            super.visit(column);
        }

        @Override
        public void visit(PlainSelect plainSelect) {
            if (plainSelect.getSelectItems() != null) {
                wildcardSelected |= plainSelect.getSelectItems().stream()
                        .map(item -> item.getExpression())
                        .anyMatch(expression -> expression instanceof AllColumns
                                || expression instanceof AllTableColumns);
            }
            super.visit(plainSelect);
        }

        private String resolveTable(String qualifier) {
            String canonical = SqlIdentifierCanonicalizer.qualifiedName(qualifier);
            if (allowedTables.contains(canonical)) {
                return canonical;
            }
            return tableQualifiers.get(SqlIdentifierCanonicalizer.simpleName(canonical));
        }

        private void registerQualifier(String qualifier, String table) {
            tableQualifiers.merge(qualifier, table,
                    (existing, candidate) -> existing.equals(candidate) ? existing : "");
        }

        private static String canonicalTable(Table table) {
            List<String> parts = table.getNameParts();
            StringBuilder canonical = new StringBuilder();
            for (int index = parts.size() - 1; index >= 0; index--) {
                if (!canonical.isEmpty()) {
                    canonical.append('.');
                }
                canonical.append(SqlIdentifierCanonicalizer.identifier(parts.get(index)));
            }
            return canonical.toString();
        }

        private Set<String> physicalTables() {
            return physicalTables;
        }

        private List<Column> columns() {
            return columns;
        }

        private boolean wildcardSelected() {
            return wildcardSelected;
        }
    }
}
