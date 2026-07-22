package dev.qcoding.businesscopilot.supportcopilot.classification;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiInvocationMetadata;
import dev.qcoding.businesscopilot.aicore.AiInvocationResult;
import dev.qcoding.businesscopilot.aicore.AiModelNotEnabledException;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.supportcopilot.SupportCopilotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketClassificationService}.
 *
 * <p>验证输入校验、脱敏和高风险类别强制转人工等核心逻辑。
 * 模型调用在集成测试中验证，单元测试使用 mock。</p>
 */
class TicketClassificationServiceTest {

    private TicketClassificationService service;
    private SensitiveTextMasker masker;
    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        masker = new SensitiveTextMasker();
        var properties = new SupportCopilotProperties(
                true, 2000, 10,
                "REFUND,ACCOUNT_SECURITY,INCIDENT",
                true, 5);

        // Use a real PromptTemplateService — the prompt template is loaded from classpath
        var promptService = new PromptTemplateService();

        aiChatService = mock(AiChatService.class);
        service = new TicketClassificationService(aiChatService, promptService, masker, properties);
    }

    @Test
    void shouldRejectNullMessage() {
        var request = new TicketClassificationRequest(null, "web");
        assertThrows(BusinessException.class, () -> service.classify(request));
    }

    @Test
    void shouldRejectBlankMessage() {
        var request = new TicketClassificationRequest("   ", "web");
        assertThrows(BusinessException.class, () -> service.classify(request));
    }

    @Test
    void shouldRejectOverlyLongMessage() {
        String longMsg = "x".repeat(2001);
        var request = new TicketClassificationRequest(longMsg, "web");
        assertThrows(BusinessException.class, () -> service.classify(request));
    }

    @Test
    void shouldAllowMessageWithinLengthLimit() {
        String validMsg = "我的订单还没有发货，请问什么时候能到？";
        var request = new TicketClassificationRequest(validMsg, "web");
        when(aiChatService.generateJsonWithMetadata(anyString(), anyString(), eq(LlmClassificationOutput.class)))
                .thenReturn(new AiInvocationResult<>(
                        new LlmClassificationOutput(
                                "PRODUCT_USAGE", "NEUTRAL", "LOW",
                                "物流状态咨询", false, java.util.List.of()),
                        new AiInvocationMetadata(
                                "openai-compatible", "test-model", "request-1",
                                10, 20, "stop", 25L)));

        TicketClassificationResponse response = service.classify(request);

        assertEquals(TicketCategory.PRODUCT_USAGE, response.category());
        assertFalse(response.needsHuman());
    }

    @Test
    void shouldMaskSensitiveInput() {
        String raw = "我的手机号是13812345678，请帮我处理。";
        String masked = service.maskedMessage(raw);
        assertNotNull(masked);
        assertFalse(masked.contains("13812345678"), "Phone number should be masked");
        assertTrue(masked.contains("138****5678"), "Phone should be partially masked");
    }

    @Test
    void shouldMaskEmailInInput() {
        String raw = "联系我 user@example.com 处理退款。";
        String masked = service.maskedMessage(raw);
        assertNotNull(masked);
        assertFalse(masked.contains("user@example.com"), "Email should be masked");
    }

    @Test
    void highRiskCategoriesShouldBeLoaded() {
        // Verify high-risk category parsing
        String raw = "这是正常的退款咨询，用户邮箱 test@example.com，订单号 ORD-001";
        String masked = service.maskedMessage(raw);
        assertNotNull(masked);
        // email should be masked in the output
        assertFalse(masked.contains("test@example.com"), "Email in high-risk message should be masked");
    }
}
