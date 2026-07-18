-- V17：允许 Knowledge Copilot 切换不同维度的 embedding 模型。
--
-- V4 是已经发布并可能执行过的历史迁移，不能通过直接修改 V4 来适配新模型。
-- pgvector 的无 typmod vector 列可以保存不同维度的向量；应用层仍会校验同一次
-- 索引任务的实际维度，检索也只会使用当前已重建并启用的文档。

ALTER TABLE knowledge_chunk_embeddings
    ALTER COLUMN embedding TYPE vector
    USING embedding::vector;

COMMENT ON COLUMN knowledge_chunk_embeddings.embedding IS
    '由应用校验维度的知识分片向量；切换模型后必须重建相关文档索引';
