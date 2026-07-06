package dev.qcoding.businesscopilot.datacopilot.schema;

import java.util.List;

/**
 * Aggregated schema context suitable for injection into LLM prompts.
 *
 * <p>Schema 上下文，用于注入 LLM prompt。只包含白名单内的表，
 * 不包含审计表或系统表，也不暴露数据库连接信息。</p>
 *
 * @param tables    whitelisted table schemas
 * @param textSummary formatted text summary for prompt injection
 */
public record SchemaContext(
        List<TableSchema> tables,
        String textSummary) {
}
