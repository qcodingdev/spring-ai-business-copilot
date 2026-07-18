package dev.qcoding.businesscopilot.aicore;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        AiChatService service = new AiChatService(emptyProvider(), props);

        assertThatThrownBy(() -> service.generateText("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("已被配置项");
    }

    @Test
    void throwsWhenNoChatClientBuilderAvailable() {
        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiChatService service = new AiChatService(emptyProvider(), props);

        assertThatThrownBy(() -> service.generateText("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("未配置 AI 对话模型");
    }

    @Test
    @SuppressWarnings("unchecked")
    void throwsClearErrorWhenChatClientBuilderProviderFails() {
        ObjectProvider<org.springframework.ai.chat.client.ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new NoSuchBeanDefinitionException("chatModel"));
        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiChatService service = new AiChatService(provider, props);

        assertThat(service.isModelEnabled()).isFalse();
        assertThatThrownBy(() -> service.generateText("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("未配置 AI 对话模型");
    }

    @Test
    void isModelEnabledReturnsFalseWhenDisabled() {
        AiModelProperties props = new AiModelProperties("test", true, 1000);
        AiChatService service = new AiChatService(emptyProvider(), props);

        assertThat(service.isModelEnabled()).isFalse();
    }

    @Test
    void modelNameReflectsConfiguration() {
        AiModelProperties props = new AiModelProperties("gpt-5-mini", false, 1000);
        AiChatService service = new AiChatService(emptyProvider(), props);

        assertThat(service.modelName()).isEqualTo("gpt-5-mini");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateJsonUsesSpringAiStructuredOutputWithSchemaValidation() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.EntityParamSpec entityParamSpec = mock(ChatClient.EntityParamSpec.class);
        StructuredOutput expected = new StructuredOutput("ready");

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user("return a structured response")).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(entityParamSpec.validateSchema()).thenReturn(entityParamSpec);
        when(responseSpec.entity(
                org.mockito.ArgumentMatchers.eq(StructuredOutput.class),
                org.mockito.ArgumentMatchers.<Consumer<ChatClient.EntityParamSpec>>any()))
                .thenAnswer(invocation -> {
                    Consumer<ChatClient.EntityParamSpec> spec = invocation.getArgument(1);
                    spec.accept(entityParamSpec);
                    return expected;
                });

        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);
        AiChatService service = new AiChatService(provider, new AiModelProperties("test", false, 1000));

        assertThat(service.generateJson("return a structured response", StructuredOutput.class)).isEqualTo(expected);
        verify(entityParamSpec).validateSchema();
    }

    private record StructuredOutput(String status) {
    }
}
