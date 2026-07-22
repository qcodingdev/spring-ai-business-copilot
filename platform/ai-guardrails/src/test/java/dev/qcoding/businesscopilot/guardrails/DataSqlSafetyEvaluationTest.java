package dev.qcoding.businesscopilot.guardrails;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Data Copilot 固定安全评测集，防止规则升级时误放行危险 SQL 或误阻断常见查询。 */
class DataSqlSafetyEvaluationTest {

    @Test
    void fixedSqlSafetySetRemainsStable() throws Exception {
        var resource = getClass().getResourceAsStream("/evals/data-sql-safety.tsv");
        assertThat(resource).isNotNull();
        List<String> lines = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertThat(lines).as("Data 固定评测集不能缩减到 15 条以下").hasSizeGreaterThanOrEqualTo(15);
        assertThat(lines).anyMatch(line -> line.startsWith("true\t"));
        assertThat(lines).anyMatch(line -> line.startsWith("false\t"));

        GuardrailsProperties properties = new GuardrailsProperties(null, null, null, 0, true);
        SensitiveFieldPolicy sensitiveFieldPolicy = new SensitiveFieldPolicy(properties);
        SqlGuardrailService guardrail = new SqlGuardrailService(List.of(
                new SingleStatementValidator(),
                new ReadOnlyStatementValidator(),
                new ForbiddenKeywordValidator(),
                new SchemaWhitelistValidator(properties.queryableTables()),
                new ColumnWhitelistValidator(properties.queryableColumns()),
                new FunctionAllowlistValidator(properties.allowedAggregateFunctions()),
                new SensitiveFieldValidator(sensitiveFieldPolicy),
                new LimitRequiredValidator(properties.defaultMaxRows(), properties.requireLimit(),
                        properties.allowedAggregateFunctions())));

        for (String line : lines) {
            String[] fields = line.split("\t", 2);
            assertThat(guardrail.validate(fields[1], properties).passed())
                    .as(line).isEqualTo(Boolean.parseBoolean(fields[0]));
        }
    }
}
