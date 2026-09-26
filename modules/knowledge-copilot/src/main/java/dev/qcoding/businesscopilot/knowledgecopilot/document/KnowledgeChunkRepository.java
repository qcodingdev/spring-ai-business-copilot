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

    /**
     * 中文友好的有限关键词检索。
     *
     * <p>只接受应用生成的有限词项，并仍然限制为当前、已索引、已启用文档。</p>
     */
    List<TextSearchResult> findByKeywordSearch(List<String> terms, int limit);

    /**
     * 复核分片所属文档当前是否仍满足检索可见条件（启用、当前版本、已索引、未过期、无冲突、ACL 允许）。
     *
     * <p>KNOW-03：检索结果回表存在时间窗口，消费前必须复核，防止使用已失效资料。</p>
     */
    boolean isDocumentVisible(Long documentId);

    record TextSearchResult(Long chunkId, double rank) {
    }
}
