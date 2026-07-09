package dev.qcoding.businesscopilot.aicore;

import dev.qcoding.businesscopilot.commonweb.api.BusinessException;
import dev.qcoding.businesscopilot.commonweb.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiEmbeddingServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<EmbeddingModel> emptyProvider() {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void throwsWhenModelDisabled() {
        AiModelProperties props = new AiModelProperties("test", true, 1000);
        AiEmbeddingService service = new AiEmbeddingService(emptyProvider(), props);

        assertThatThrownBy(() -> service.embed("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void throwsWhenNoEmbeddingModelAvailable() {
        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiEmbeddingService service = new AiEmbeddingService(emptyProvider(), props);

        assertThatThrownBy(() -> service.embed("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("No AI embedding model");
    }

    @Test
    @SuppressWarnings("unchecked")
    void throwsClearErrorWhenEmbeddingModelProviderFails() {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new NoSuchBeanDefinitionException("embeddingModel"));
        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiEmbeddingService service = new AiEmbeddingService(provider, props);

        assertThat(service.isModelEnabled()).isFalse();
        assertThatThrownBy(() -> service.embed("test"))
                .isInstanceOf(AiModelNotEnabledException.class)
                .hasMessageContaining("No AI embedding model");
    }

    @Test
    void isModelEnabledReturnsFalseWhenDisabled() {
        AiModelProperties props = new AiModelProperties("test", true, 1000);
        AiEmbeddingService service = new AiEmbeddingService(emptyProvider(), props);

        assertThat(service.isModelEnabled()).isFalse();
    }

    @Test
    void modelNameReflectsConfiguration() {
        AiModelProperties props = new AiModelProperties("gpt-5-mini", false, 1000);
        AiEmbeddingService service = new AiEmbeddingService(emptyProvider(), props);

        assertThat(service.modelName()).isEqualTo("gpt-5-mini");
    }

    @Test
    @SuppressWarnings("unchecked")
    void embedReturnsVectorOnSuccess() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] expected = new float[]{0.1f, 0.2f, 0.3f};
        when(mockModel.embed("test")).thenReturn(expected);

        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockModel);

        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiEmbeddingService service = new AiEmbeddingService(provider, props);

        float[] result = service.embed("test");
        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    @SuppressWarnings("unchecked")
    void embedWrapsRuntimeExceptionAsBusinessException() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockModel.embed("test")).thenThrow(new RuntimeException("API timeout"));

        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockModel);

        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiEmbeddingService service = new AiEmbeddingService(provider, props);

        assertThatThrownBy(() -> service.embed("test"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI embedding model invocation failed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dimensionsDelegatesToModel() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockModel.dimensions()).thenReturn(1536);

        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockModel);

        AiModelProperties props = new AiModelProperties("test", false, 1000);
        AiEmbeddingService service = new AiEmbeddingService(provider, props);

        assertThat(service.dimensions()).isEqualTo(1536);
    }
}
