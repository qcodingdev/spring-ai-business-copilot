package dev.qcoding.businesscopilot.knowledgecopilot.document;

import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ParsedSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses plain-text documents by splitting on blank lines (double newline).
 *
 * <p>TXT 文档解析器。按空行切分段落，每段作为一个 {@link ParsedSection}。
 * sectionTitle 设为段落序号（如 "段落 1"），便于溯源和引用。</p>
 *
 * <p>连续多个空行视为单个分隔符；文本开头或结尾的空行被忽略。</p>
 */
public class TextDocumentParser implements DocumentParser {

    private static final String PARAGRAPH_TITLE_PREFIX = "段落 ";

    @Override
    public List<ParsedSection> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String[] blocks = content.split("\\n\\s*\\n");
        List<ParsedSection> sections = new ArrayList<>();
        int paragraphIndex = 1;

        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            sections.add(new ParsedSection(PARAGRAPH_TITLE_PREFIX + paragraphIndex, trimmed));
            paragraphIndex++;
        }
        return sections;
    }
}
