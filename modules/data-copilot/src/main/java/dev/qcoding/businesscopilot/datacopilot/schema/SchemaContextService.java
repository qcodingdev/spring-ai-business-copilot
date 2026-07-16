package dev.qcoding.businesscopilot.datacopilot.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link SchemaContext} from whitelisted table metadata, formatted for LLM prompts.
 *
 * <p>Schema 上下文服务。读取白名单表结构，合并字段描述和敏感标记，
 * 输出适合 prompt 注入的文本摘要。审计表不会进入上下文。</p>
 */
public class SchemaContextService {

    private static final Logger log = LoggerFactory.getLogger(SchemaContextService.class);

    private final SchemaMetadataRepository repository;
    private final DataCopilotSchemaProperties properties;

    public SchemaContextService(SchemaMetadataRepository repository, DataCopilotSchemaProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Build the full schema context from current database metadata. */
    public SchemaContext buildContext() {
        List<String> tableNames = repository.findQueryableTableNames();
        List<TableSchema> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            try {
                List<ColumnSchema> columns = repository.findColumns(tableName);
                String description = properties.columnDescriptions().get(tableName.toLowerCase());
                if ((description == null || description.isBlank()) && tableName.contains(".")) {
                    description = properties.columnDescriptions().get(
                            tableName.substring(tableName.indexOf('.') + 1).toLowerCase());
                }
                if (description == null || description.isBlank()) {
                    description = "Table " + tableName;
                }
                tables.add(new TableSchema(tableName, columns, description));
            } catch (RuntimeException ex) {
                // 单表读取失败不应阻断整个 schema 构建过程
                log.warn("Failed to read schema for table {}: {}", tableName, ex.getMessage());
            }
        }
        String summary = renderTextSummary(tables);
        return new SchemaContext(tables, summary);
    }

    /** Render the schema as a compact text block for prompt injection. */
    private String renderTextSummary(List<TableSchema> tables) {
        StringBuilder sb = new StringBuilder();
        for (TableSchema table : tables) {
            sb.append("Table: ").append(table.name());
            if (table.description() != null && !table.description().isBlank()) {
                sb.append("  -- ").append(table.description());
            }
            sb.append('\n');
            sb.append("Columns:\n");
            for (ColumnSchema col : table.columns()) {
                // schema 文本不暴露连接信息，只列出列名、类型和描述
                sb.append("  - ").append(col.name()).append(" (").append(col.type()).append(")");
                if (col.description() != null && !col.description().isBlank()) {
                    sb.append(": ").append(col.description());
                }
                if (col.sensitive()) {
                    sb.append(" [sensitive]");
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        String result = sb.toString();
        // 控制长度，避免 prompt 过长
        if (result.length() > properties.maxSchemaTextLength()) {
            result = result.substring(0, properties.maxSchemaTextLength()) + "\n... (schema truncated)";
        }
        return result;
    }

    /** Whether a given table name is whitelisted. */
    public boolean isQueryable(String tableName) {
        if (tableName == null) return false;
        try {
            return properties.queryableTables()
                    .contains(QualifiedTableName.parse(tableName).canonicalName());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
