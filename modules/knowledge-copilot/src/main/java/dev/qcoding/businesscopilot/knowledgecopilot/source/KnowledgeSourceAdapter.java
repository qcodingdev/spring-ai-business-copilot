package dev.qcoding.businesscopilot.knowledgecopilot.source;

import java.time.Instant;
import java.util.List;

/** 将外部来源规范化为有版本、删除和 ACL 的资料项。 */
public interface KnowledgeSourceAdapter {

    boolean supports(KnowledgeSourceProvider provider);

    SourceBatch fetch(KnowledgeSourceConnection connection, String cursor);

    record SourceItem(
            String sourceItemId,
            String fileName,
            String contentType,
            byte[] content,
            String version,
            String etag,
            Instant sourceUpdatedAt,
            List<String> allowedGroups,
            boolean deleted) {
        public SourceItem {
            allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
        }
    }

    record SourceBatch(List<SourceItem> items, String nextCursor, boolean fullSnapshot) {
        public SourceBatch {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
