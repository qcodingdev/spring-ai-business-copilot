package dev.qcoding.businesscopilot.datacopilot.generation;

import java.util.List;

/**
 * Structured output from the LLM for SQL generation.
 *
 * <p>模型输出的结构化 JSON，包含 SQL、摘要、假设和警告。</p>
 *
 * @param sql         generated SQL statement
 * @param summary     concise description of what this query computes
 * @param assumptions assumptions made about ambiguous requirements
 * @param warnings    optional caveats
 */
public record GeneratedSqlCandidate(
        String sql,
        String summary,
        List<String> assumptions,
        List<String> warnings) {
}
