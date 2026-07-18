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
        ReplyDraftGuardrailService guardrail = new ReplyDraftGuardrailService(
                new SupportCopilotProperties(true, 2000, 10,
                        "REFUND,ACCOUNT_SECURITY,INCIDENT", true, 5));

        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            boolean expected = Boolean.parseBoolean(fields[0]);
            List<LlmReplyDraftOutput.LlmCitation> citations = fields[3].isBlank()
                    ? List.of() : List.of(new LlmReplyDraftOutput.LlmCitation(fields[3], "fixed eval"));
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
