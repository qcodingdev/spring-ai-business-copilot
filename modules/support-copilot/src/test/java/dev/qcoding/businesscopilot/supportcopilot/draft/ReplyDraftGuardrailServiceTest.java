package dev.qcoding.businesscopilot.supportcopilot.draft;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplyDraftGuardrailServiceTest {

    private final ReplyDraftGuardrailService service = new ReplyDraftGuardrailService(
            new SupportCopilotProperties(true, 2000, 10,
                    "REFUND,ACCOUNT_SECURITY,INCIDENT", true, 5));

    @Test
    void shouldRejectReplyWithoutEvidence() {
        var output = new LlmReplyDraftOutput(
                "您好，退款申请需要提供订单号。",
                "MEDIUM",
                List.of(),
                List.of(),
                true);

        assertThrows(BusinessException.class, () -> service.validate(output, ""));
    }

    @Test
    void shouldRejectReplyWithoutCitation() {
        var output = new LlmReplyDraftOutput(
                "您好，退款申请需要提供订单号。",
                "MEDIUM",
                List.of(),
                List.of(),
                true);

        assertThrows(BusinessException.class, () -> service.validate(output, "chunk-1"));
    }

    @Test
    void shouldRejectCitationOutsideEvidence() {
        var output = new LlmReplyDraftOutput(
                "您好，退款申请需要提供订单号。",
                "MEDIUM",
                List.of(),
                List.of(new LlmReplyDraftOutput.LlmCitation("chunk-2", "退款材料依据")),
                true);

        assertThrows(BusinessException.class, () -> service.validate(output, "chunk-1"));
    }

    @Test
    void shouldAllowBusinessTermRefundWhenNotCommitted() {
        var output = new LlmReplyDraftOutput(
                "您好，根据退款流程，申请退款需要提供订单号和问题截图。",
                "MEDIUM",
                List.of(),
                List.of(new LlmReplyDraftOutput.LlmCitation("chunk-1", "退款材料依据")),
                true);

        assertDoesNotThrow(() -> service.validate(output, "chunk-1"));
    }

    @Test
    void shouldRejectExplicitRefundCommitment() {
        var output = new LlmReplyDraftOutput(
                "您好，我们承诺退款，请等待处理。",
                "HIGH",
                List.of(),
                List.of(new LlmReplyDraftOutput.LlmCitation("chunk-1", "退款材料依据")),
                true);

        assertThrows(BusinessException.class, () -> service.validate(output, "chunk-1"));
    }
}
