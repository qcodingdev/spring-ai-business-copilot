-- DATA-04：SQL 候选有限修正链（预算内逐次修正，旧凭证失效）。
CREATE TABLE IF NOT EXISTS data_candidate_revisions (
    id                 BIGSERIAL PRIMARY KEY,
    candidate_id       VARCHAR(64) UNIQUE NOT NULL,
    root_candidate_id  VARCHAR(64) NOT NULL,
    revised_from       VARCHAR(64) NOT NULL,
    revision_index     INTEGER NOT NULL,
    question           VARCHAR(1000) NOT NULL,
    instruction        VARCHAR(1000) NOT NULL,
    actor_id           VARCHAR(100) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_data_candidate_revisions_root
    ON data_candidate_revisions(root_candidate_id, revision_index);

-- DATA-05：查询到报告的可追溯关联（草稿 ← 交接 ← 结果快照 ← SQL 候选）。
CREATE TABLE IF NOT EXISTS report_draft_data_links (
    id               BIGSERIAL PRIMARY KEY,
    draft_id         BIGINT NOT NULL,
    source_reference VARCHAR(200) NOT NULL,
    query_result_id  BIGINT,
    candidate_id     VARCHAR(64),
    handoff_id       BIGINT,
    linked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (draft_id, source_reference)
);

CREATE INDEX IF NOT EXISTS idx_report_draft_data_links_draft
    ON report_draft_data_links(draft_id);
