package dev.qcoding.businesscopilot.knowledgecopilot.document;

import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ParsedSection;

import java.util.List;

/**
 * Parses raw document text into titled sections for chunking.
 *
 * <p>文档解析器接口。不同格式（Markdown、TXT）提供各自的实现，
 * 输出统一的 {@link ParsedSection} 列表供 {@code ChunkingService} 分片。</p>
 */
public interface DocumentParser {

    /**
     * Parse the given document text into sections.
     *
     * @param content raw document text
     * @return ordered list of sections, never {@code null}
     */
    List<ParsedSection> parse(String content);
}
