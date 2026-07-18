package dev.qcoding.businesscopilot.knowledgecopilot.document;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link KnowledgeChunk} persistence.
 *
 * <p>知识分片持久化接口。文档上传时批量保存分片；文档停用或重建索引前按 documentId 删除。
 * 检索时按 id 单条查询 chunk 完整内容。</p>
 */
public interface KnowledgeChunkRepository {

    /** Save all chunks for a document in a single batch. */
    void saveAll(List<KnowledgeChunk> chunks);

    /** Delete all chunks belonging to a document. Returns the number of deleted rows. */
    int deleteByDocumentId(Long documentId);

    /** Find all chunks for a document, ordered by chunkIndex. */
    List<KnowledgeChunk> findByDocumentId(Long documentId);

    /** Find a single chunk by its primary key. */
    Optional<KnowledgeChunk> findById(Long id);

    /** PostgreSQL full-text retrieval from current, indexed, enabled document versions. */
    List<TextSearchResult> findByTextSearch(String query, int limit);

    record TextSearchResult(Long chunkId, double rank) {
    }
}
