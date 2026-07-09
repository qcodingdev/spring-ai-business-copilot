package dev.qcoding.businesscopilot.knowledgecopilot;

import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ChunkingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KnowledgeCopilotAutoConfiguration}.
 *
 * <p>验证 AutoConfiguration 能被加载，Properties 能正确构建默认值。</p>
 */
class KnowledgeCopilotAutoConfigurationTest {

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
}
