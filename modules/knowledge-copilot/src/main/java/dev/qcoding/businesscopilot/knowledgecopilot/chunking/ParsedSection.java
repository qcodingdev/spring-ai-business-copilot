package dev.qcoding.businesscopilot.knowledgecopilot.chunking;

/**
 * A contiguous text block extracted from a document, tied to a section title.
 *
 * <p>解析器（{@code DocumentParser}）产出的中间结构：标题层级下的原始文本块。
 * 分片服务（{@link ChunkingService}）据此做长度裁剪和重叠合并。</p>
 *
 * @param sectionTitle section heading the block belongs to, may be {@code null} for untitled text
 * @param text         raw text of the block
 */
public record ParsedSection(
        String sectionTitle,
        String text) {
}
