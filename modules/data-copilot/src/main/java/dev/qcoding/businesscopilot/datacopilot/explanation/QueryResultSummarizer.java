package dev.qcoding.businesscopilot.datacopilot.explanation;

import dev.qcoding.businesscopilot.datacopilot.query.QueryColumn;
import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;
import dev.qcoding.businesscopilot.datacopilot.query.QueryRow;

import java.util.StringJoiner;

/**
 * Summarizes a {@link QueryResultTable} into a compact text block for prompt injection.
 *
 * <p>查询结果摘要生成器。只把脱敏后的列名、行数和少量样例行传给模型，
 * 不传完整大结果。摘要格式清晰，便于模型理解表格结构。</p>
 */
public class QueryResultSummarizer {

    /** Maximum sample rows to include in the summary. */
    private static final int MAX_SAMPLE_ROWS = 5;

    /**
     * Build a text summary of the query result for the LLM prompt.
     *
     * @param result the masked query result table
     * @return a compact text summary
     */
    public String summarize(QueryResultTable result) {
        if (result == null) {
            return "No result available.";
        }

        StringJoiner joiner = new StringJoiner("\n");

        // 列名
        joiner.add("Columns: " + result.columns().stream()
                .map(QueryColumn::name)
                .toList());

        // 行数和截断标记
        joiner.add("Row count: " + result.rowCount());
        if (result.truncated()) {
            joiner.add("(Result truncated — more rows exist in the database)");
        }

        // 少量样例行
        if (result.rows().isEmpty()) {
            joiner.add("Sample rows: (empty — no matching data)");
        } else {
            int sampleCount = Math.min(result.rows().size(), MAX_SAMPLE_ROWS);
            joiner.add("Sample rows (" + sampleCount + " of " + result.rowCount() + "):");
            for (int i = 0; i < sampleCount; i++) {
                QueryRow row = result.rows().get(i);
                joiner.add("  " + row.values());
            }
        }

        return joiner.toString();
    }
}
