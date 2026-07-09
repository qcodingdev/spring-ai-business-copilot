package dev.qcoding.businesscopilot.knowledgecopilot.document;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link KnowledgeDocument} persistence.
 *
 * <p>知识文档持久化接口。提供文档的增删查改，以及按 contentHash 去重检查。</p>
 */
public interface KnowledgeDocumentRepository {

    /** Save a new document and return the generated ID. */
    Long save(KnowledgeDocument document);

    /** Find a document by its primary key. */
    Optional<KnowledgeDocument> findById(Long id);

    /** List all documents ordered by creation time descending. */
    List<KnowledgeDocument> findAll();

    /** Check whether a document with the given content hash already exists. */
    boolean existsByContentHash(String contentHash);

    /** Update the enabled flag of a document. Returns true if the row was updated. */
    boolean updateEnabled(Long id, boolean enabled);
}
