package dev.qcoding.businesscopilot.aicore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiChatServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<org.springframework.ai.chat.client.ChatClient.Builder> emptyProvider() {
        ObjectProvider<org.springframework.ai.chat.client.ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void throwsWhenModelDisabled() {
        AiModelProperties props = new AiModelProperties("test", true, 1000);
        AiChatService service = new AiChatService(emptyProvider(), new JsonOutputParser(), props);

        assertThatThrownBy(() -> service.generateText("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void throwsWhenNoChatClientBuilderAvailable() {
        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiChatService service = new AiChatService(emptyProvider(), new JsonOutputParser(), props);

        assertThatThrownBy(() -> service.generateText("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("No AI chat model");
    }

    @Test
    void isModelEnabledReturnsFalseWhenDisabled() {
        AiModelProperties props = new AiModelProperties("test", true, 1000);
        AiChatService service = new AiChatService(emptyProvider(), new JsonOutputParser(), props);

        assertThat(service.isModelEnabled()).isFalse();
    }

    @Test
    void modelNameReflectsConfiguration() {
        AiModelProperties props = new AiModelProperties("gpt-5-mini", false, 1000);
        AiChatService service = new AiChatService(emptyProvider(), new JsonOutputParser(), props);

        assertThat(service.modelName()).isEqualTo("gpt-5-mini");
    }
}
