package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonOutputParserTest {

    private final JsonOutputParser parser = new JsonOutputParser();

    @Test
    void parsesCleanJson() {
        String raw = """
                {"sql": "select 1", "summary": "test", "assumptions": [], "warnings": []}
                """;
        SqlCandidateOutput result = parser.parse(raw, SqlCandidateOutput.class);
        assertThat(result.sql()).isEqualTo("select 1");
        assertThat(result.summary()).isEqualTo("test");
    }

    @Test
    void stripsFencedCodeBlock() {
        String raw = """
                Here is the SQL:
                ```json
                {"sql": "select 1", "summary": "test", "assumptions": [], "warnings": []}
                ```
                """;
        SqlCandidateOutput result = parser.parse(raw, SqlCandidateOutput.class);
        assertThat(result.sql()).isEqualTo("select 1");
    }

    @Test
    void extractsJsonObjectFromSurroundingText() {
        String raw = """
                Based on the schema, the query is:
                {"sql": "select * from orders limit 10", "summary": "orders", "assumptions": [], "warnings": []}
                This query retrieves the top 10 orders.
                """;
        SqlCandidateOutput result = parser.parse(raw, SqlCandidateOutput.class);
        assertThat(result.sql()).isEqualTo("select * from orders limit 10");
    }

    @Test
    void throwsOnEmptyOutput() {
        assertThatThrownBy(() -> parser.parse("", SqlCandidateOutput.class))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_OUTPUT_PARSE_ERROR);
    }

    @Test
    void throwsOnInvalidJson() {
        assertThatThrownBy(() -> parser.parse("not json at all", SqlCandidateOutput.class))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_OUTPUT_PARSE_ERROR);
    }

    /** Internal test DTO matching the SQL generation output shape. */
    record SqlCandidateOutput(String sql, String summary, java.util.List<String> assumptions,
                              java.util.List<String> warnings) {
    }
}
