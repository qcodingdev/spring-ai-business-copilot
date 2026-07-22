package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportReplyGuardrailEvaluationTest {

    @Test
    void fixedReplySafetySetRemainsStable() throws Exception {
        var resource = getClass().getResourceAsStream("/evals/reply-guardrails.tsv");
        assertThat(resource).isNotNull();
        List<String> lines = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertThat(lines).as("Support 固定评测集不能缩减到 10 条以下").hasSizeGreaterThanOrEqualTo(10);
        ReplyDraftGuardrailService guardrail = new ReplyDraftGuardrailService(
                new SupportCopilotProperties(true, 2000, 10,
                        "REFUND,ACCOUNT_SECURITY,INCIDENT", true, 5));

        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            boolean expected = Boolean.parseBoolean(fields[0]);
            // citation_id 是可选末列；文件不保留尾随空白时按空引用处理。
            String citationId = fields.length > 3 ? fields[3] : "";
            List<LlmReplyDraftOutput.LlmCitation> citations = citationId.isBlank()
                    ? List.of() : List.of(new LlmReplyDraftOutput.LlmCitation(citationId, "fixed eval"));
            LlmReplyDraftOutput output = new LlmReplyDraftOutput(
                    fields[1], "MEDIUM", List.of(), citations, false);
            if (expected) {
                assertThatCode(() -> guardrail.validate(output, fields[2])).as(line).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> guardrail.validate(output, fields[2]))
                        .as(line).isInstanceOf(BusinessException.class);
            }
        }
    }
}
