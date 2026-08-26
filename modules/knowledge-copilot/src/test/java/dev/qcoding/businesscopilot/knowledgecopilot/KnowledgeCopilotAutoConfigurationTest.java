package dev.qcoding.businesscopilot.knowledgecopilot;

import dev.qcoding.businesscopilot.aicore.AiChatService;
import dev.qcoding.businesscopilot.aicore.AiEmbeddingService;
import dev.qcoding.businesscopilot.aicore.PromptTemplateService;
import dev.qcoding.businesscopilot.guardrails.SensitiveTextMasker;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActorProvider;
import dev.qcoding.businesscopilot.commonsecurity.CurrentActor;
import dev.qcoding.businesscopilot.commonsecurity.CommonSecurityAutoConfiguration;
import dev.qcoding.businesscopilot.documentprocessing.DocumentTextExtractor;
import dev.qcoding.businesscopilot.knowledgecopilot.answer.KnowledgeQuestionService;
import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingProperties;
import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeDocumentRepository;
import dev.qcoding.businesscopilot.knowledgecopilot.indexing.KnowledgeIndexLifecycleService;
import dev.qcoding.businesscopilot.knowledgecopilot.web.KnowledgeCopilotController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link KnowledgeCopilotAutoConfiguration}.
 *
 * <p>验证 AutoConfiguration 能被加载，Properties 能正确构建默认值。</p>
 */
class KnowledgeCopilotAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CommonSecurityAutoConfiguration.class,
                    KnowledgeCopilotAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(AiChatService.class, () -> mock(AiChatService.class))
            .withBean(AiEmbeddingService.class, () -> mock(AiEmbeddingService.class))
            .withBean(PromptTemplateService.class, PromptTemplateService::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SensitiveTextMasker.class, SensitiveTextMasker::new)
            .withBean(DocumentTextExtractor.class, () -> mock(DocumentTextExtractor.class))
            .withBean(CurrentActorProvider.class, () -> () -> new CurrentActor("test", java.util.Set.of()));

    @Test
    @DisplayName("AutoConfiguration 类存在且可实例化")
    void autoConfigurationClassExists() {
        assertThat(KnowledgeCopilotAutoConfiguration.class).isNotNull();
    }

    @Test
    @DisplayName("Properties 使用零值构造后能填充默认值")
    void propertiesFillsDefaultsWhenZeroValues() {
        KnowledgeCopilotProperties props = new KnowledgeCopilotProperties(false, 0, 0, 0d, null, 0);

        assertThat(props.enabled()).isFalse();
        assertThat(props.maxDocumentSize()).isEqualTo(2L * 1024 * 1024);
        assertThat(props.topK()).isEqualTo(5);
        assertThat(props.minSimilarity()).isCloseTo(0.70d, org.assertj.core.data.Offset.offset(0.001));
        assertThat(props.embeddingModelName()).isEqualTo("text-embedding-3-small");
        assertThat(props.embeddingDimension()).isEqualTo(1536);
        assertThat(props.indexStaleAfter()).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Properties 保留显式传入的非零值")
    void propertiesKeepsExplicitValues() {
        KnowledgeCopilotProperties props = new KnowledgeCopilotProperties(
                true, 5_000_000L, 10, 0.85d, "my-embedding-model", 3072);

        assertThat(props.enabled()).isTrue();
        assertThat(props.maxDocumentSize()).isEqualTo(5_000_000L);
        assertThat(props.topK()).isEqualTo(10);
        assertThat(props.minSimilarity()).isCloseTo(0.85d, org.assertj.core.data.Offset.offset(0.001));
        assertThat(props.embeddingModelName()).isEqualTo("my-embedding-model");
        assertThat(props.embeddingDimension()).isEqualTo(3072);
    }

    @Test
    @DisplayName("ChunkingProperties 负值重叠被修正为零")
    void propertiesNegativeOverlapCorrected() {
        ChunkingProperties chunking = new ChunkingProperties(800, -1);
        assertThat(chunking.chunkOverlap()).isZero();
    }

    @Test
    void isNotRegisteredWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(KnowledgeQuestionService.class);
            assertThat(context).doesNotHaveBean(KnowledgeDocumentRepository.class);
        });
    }

    @Test
    void isRegisteredWhenEnabled() {
        contextRunner.withPropertyValues("business-copilot.knowledge.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(KnowledgeQuestionService.class);
                    assertThat(context).hasSingleBean(KnowledgeDocumentRepository.class);
                    assertThat(context).hasSingleBean(KnowledgeIndexLifecycleService.class);
                    assertThat(context).hasSingleBean(KnowledgeCopilotController.class);
                });
    }
}
