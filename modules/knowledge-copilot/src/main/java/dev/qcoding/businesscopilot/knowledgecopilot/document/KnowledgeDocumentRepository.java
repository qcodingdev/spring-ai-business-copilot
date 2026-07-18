package dev.qcoding.businesscopilot.knowledgecopilot.document;

import java.util.List;
import java.util.Optional;

/** 知识文档持久化接口，提供版本管理和按 contentHash 去重检查。 */
public interface KnowledgeDocumentRepository {

    /** 保存文档并返回数据库生成的 ID。 */
    Long save(KnowledgeDocument document);

    /** 按主键查询文档。 */
    Optional<KnowledgeDocument> findById(Long id);

    /** 按创建时间倒序列出文档。 */
    List<KnowledgeDocument> findAll();

    /** 检查指定内容摘要的文档是否存在。 */
    boolean existsByContentHash(String contentHash);

    /** 更新文档启用状态，并返回是否更新成功。 */
    boolean updateEnabled(Long id, boolean enabled);

    /** 获取逻辑文档的下一个递增版本号。 */
    int nextVersion(java.util.UUID logicalDocumentId);

    /** 保存替代版本前，将旧版本标记为非当前版本。 */
    void supersedeCurrent(java.util.UUID logicalDocumentId);

    /** 更新异步索引状态，不暴露模型提供方原始错误。 */
    boolean updateIndexStatus(Long id, String status, String errorCategory, boolean enabled);

    /** 删除一个归属明确的文档版本及其分片和索引任务。 */
    boolean deleteById(Long id, String ownerActorId);

    /** 删除当前版本后，将同一逻辑文档最近的旧版本提升为当前版本并保持停用。 */
    void promoteLatestVersion(java.util.UUID logicalDocumentId);
}
