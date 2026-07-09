package dev.qcoding.businesscopilot.knowledgecopilot.document;

import dev.qcoding.businesscopilot.knowledgecopilot.chunking.ParsedSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Markdown documents, preserving heading hierarchy as section titles.
 *
 * <p>Markdown 文档解析器。按 ATX 标题（# ~ ######）切分章节，把每个标题下的内容
 * （直到下一个同级或更高级标题）作为一个 {@link ParsedSection}，sectionTitle 保留标题层级。</p>
 *
 * <p>标题前的前言（front matter）若存在，归入以文档标题或 "概述" 为名的初始 section。
 * 每条 section 的 text 已去除标题行本身，仅保留正文。</p>
 */
public class MarkdownDocumentParser implements DocumentParser {

    private static final String DEFAULT_SECTION_TITLE = "概述";

    /** Markdown ATX heading: 1–6 leading '#' followed by text. */
    private static final java.util.regex.Pattern HEADING_PATTERN =
            java.util.regex.Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    @Override
    public List<ParsedSection> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<ParsedSection> sections = new ArrayList<>();
        String currentTitle = DEFAULT_SECTION_TITLE;
        StringBuilder currentBody = new StringBuilder();

        for (String line : content.split("\\R", -1)) {
            java.util.regex.Matcher matcher = HEADING_PATTERN.matcher(line);
            if (matcher.matches()) {
                // 遇到新标题：先 flush 之前的 section（若有内容）
                flushSection(sections, currentTitle, currentBody);
                String hashes = matcher.group(1);
                String titleText = matcher.group(2).trim();
                // 保留层级前缀，便于检索和引用展示
                currentTitle = hashes + " " + titleText;
                currentBody = new StringBuilder();
            } else {
                if (currentBody.length() > 0) {
                    currentBody.append("\n");
                }
                currentBody.append(line);
            }
        }
        // flush 最后一个 section
        flushSection(sections, currentTitle, currentBody);
        return sections;
    }

    private void flushSection(List<ParsedSection> sections, String title, StringBuilder body) {
        String text = body.toString().trim();
        if (!text.isEmpty()) {
            sections.add(new ParsedSection(title, text));
        }
    }
}
