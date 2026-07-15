-- Existing documents created while the embedding model was disabled must not
-- participate in retrieval until their embeddings are rebuilt.
UPDATE knowledge_documents d
SET enabled = FALSE,
    updated_at = now()
WHERE d.enabled = TRUE
  AND NOT EXISTS (
      SELECT 1
      FROM knowledge_chunks c
      JOIN knowledge_chunk_embeddings e ON e.chunk_id = c.id
      WHERE c.document_id = d.id
  );
