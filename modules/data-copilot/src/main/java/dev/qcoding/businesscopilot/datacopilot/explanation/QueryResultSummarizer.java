package dev.qcoding.businesscopilot.datacopilot.explanation;

import dev.qcoding.businesscopilot.datacopilot.query.QueryColumn;
import dev.qcoding.businesscopilot.datacopilot.query.QueryResultTable;
import dev.qcoding.businesscopilot.datacopilot.query.QueryRow;

import java.util.StringJoiner;

/**
 * 将 {@link QueryResultTable} 汇总为适合注入提示词的紧凑文本。
 *
 * <p>查询结果摘要生成器。只把脱敏后的列名、行数和少量样例行传给模型，
 * 不传完整大结果。摘要格式清晰，便于模型理解表格结构。</p>
 */
public class QueryResultSummarizer {

    /** 摘要最多包含的样例行数。 */
    private static final int MAX_SAMPLE_ROWS = 5;

    /**
     * 为大模型提示词构建查询结果文本摘要。
     *
     * @param result 已脱敏的查询结果表
     * @return 紧凑的文本摘要
     */
    public String summarize(QueryResultTable result) {
        if (result == null) {
            return "没有可用查询结果。";
        }

        StringJoiner joiner = new StringJoiner("\n");

        // 列名
        joiner.add("列：" + result.columns().stream()
                .map(QueryColumn::name)
                .toList());

        // 行数和截断标记
        joiner.add("总行数：" + result.rowCount());
        if (result.truncated()) {
            joiner.add("（结果已截断，数据库中仍有更多数据）");
        }

        // 少量样例行
        if (result.rows().isEmpty()) {
            joiner.add("结果样例：（空，无匹配数据）");
        } else {
            int sampleCount = Math.min(result.rows().size(), MAX_SAMPLE_ROWS);
            joiner.add("结果样例（共 " + result.rowCount() + " 行，展示 " + sampleCount + " 行）：");
            for (int i = 0; i < sampleCount; i++) {
                QueryRow row = result.rows().get(i);
                joiner.add("  " + row.values());
            }
        }

        return joiner.toString();
    }
}
