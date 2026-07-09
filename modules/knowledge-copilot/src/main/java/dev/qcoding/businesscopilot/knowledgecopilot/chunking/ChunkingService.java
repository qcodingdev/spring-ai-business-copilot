package dev.qcoding.businesscopilot.knowledgecopilot.chunking;

import dev.qcoding.businesscopilot.knowledgecopilot.document.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits parsed document sections into indexed chunks respecting size and overlap limits.
 *
 * <p>文档分片服务。将解析器产出的 {@link ParsedSection} 列表按配置的 chunkSize 和
 * chunkOverlap 切分为 {@link KnowledgeChunk} 列表。分片优先在段落边界裁剪，
 * 超长段落再做字符级切分。</p>
 *
 * <p>分片流程：
 * <ol>
 *   <li>将所有 section 展平为文本段。</li>
 *   <li>对每个文本段：若长度 ≤ chunkSize，直接作为一个 chunk。</li>
 *   <li>若长度 &gt; chunkSize，按 chunkSize 步进切分，步进量 = chunkSize - chunkOverlap。</li>
 *   <li>记录 chunkIndex 保证全局有序。</li>
 * </ol>
 * </p>
 */
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    private final ChunkingProperties properties;

    public ChunkingService(ChunkingProperties properties) {
        this.properties = properties;
    }

    /**
     * Split sections into chunks for a given document.
     *
     * @param documentId the owning document ID
     * @param sections   parsed sections from the document
     * @return ordered list of chunks with sequential chunkIndex values
     */
    public List<KnowledgeChunk> chunk(Long documentId, List<ParsedSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }

        int chunkSize = properties.chunkSize();
        int step = Math.max(1, chunkSize - properties.chunkOverlap());

        List<KnowledgeChunk> chunks = new ArrayList<>();
        int index = 0;

        for (ParsedSection section : sections) {
            String text = section.text();
            if (text == null || text.isBlank()) {
                continue;
            }

            // 尝试按段落进一步细分：如果段落总长 ≤ chunkSize 则整段保留
            List<String> segments = splitToSegments(text, chunkSize);

            for (String segment : segments) {
                if (segment.isBlank()) {
                    continue;
                }
                // 对超长段再做字符级切分
                if (segment.length() <= chunkSize) {
                    chunks.add(buildChunk(documentId, section.sectionTitle(), index, segment));
                    index++;
                } else {
                    List<String> splits = splitBySize(segment, chunkSize, step);
                    for (String split : splits) {
                        chunks.add(buildChunk(documentId, section.sectionTitle(), index, split));
                        index++;
                    }
                }
            }
        }

        log.debug("Chunked documentId={} into {} chunks (chunkSize={}, overlap={})",
                documentId, chunks.size(), chunkSize, properties.chunkOverlap());
        return chunks;
    }

    private KnowledgeChunk buildChunk(Long documentId, String sectionTitle, int chunkIndex, String content) {
        String preview = content.length() > 200 ? content.substring(0, 200) + "…" : content;
        int tokenCount = estimateTokens(content);
        return new KnowledgeChunk(
                null,           // id assigned by DB
                documentId,
                sectionTitle,
                chunkIndex,
                content,
                preview,
                tokenCount,
                null            // createdAt assigned by DB
        );
    }

    /**
     * Split text by double-newline paragraphs; merge consecutive short paragraphs
     * until chunkSize is reached.
     */
    private List<String> splitToSegments(String text, int chunkSize) {
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() > 0 && current.length() + 1 + trimmed.length() > chunkSize) {
                segments.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            segments.add(current.toString().trim());
        }
        return segments;
    }

    /** Character-level sliding-window split with step = chunkSize - overlap. */
    private List<String> splitBySize(String text, int chunkSize, int step) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            result.add(text.substring(start, end));
            if (end == text.length()) break;
            start += step;
        }
        return result;
    }

    /** Rough token estimate: 1 token ≈ 1.5 Chinese characters or 4 English characters. */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 粗估：平均每 2 个字符约 1 个 token
        return Math.max(1, text.length() / 2);
    }
}
