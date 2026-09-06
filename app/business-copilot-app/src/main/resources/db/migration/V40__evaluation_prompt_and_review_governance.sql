-- V40: independent review, managed evaluation datasets, and governed prompt releases.
-- Management records contain synthetic evaluation inputs only. Business payloads, credentials,
-- raw customer text, resumes, and provider exceptions must never be copied into these tables.

CREATE TABLE workflow_review_tasks (
    id                  BIGSERIAL PRIMARY KEY,
    subject_type        VARCHAR(50) NOT NULL,
    subject_id          VARCHAR(100) NOT NULL,
    owner_actor_id      VARCHAR(100) NOT NULL,
    status              VARCHAR(30) NOT NULL,
    reviewer_actor_id   VARCHAR(100),
    review_note         VARCHAR(1000),
    content_version     BIGINT NOT NULL DEFAULT 1,
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at         TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_workflow_review_subject UNIQUE (subject_type, subject_id),
    CONSTRAINT ck_workflow_review_subject CHECK (
        subject_type IN ('DATA_SQL_CANDIDATE', 'REPORT_DRAFT')
    ),
    CONSTRAINT ck_workflow_review_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')
    )
);

CREATE INDEX idx_workflow_review_queue
    ON workflow_review_tasks(status, submitted_at, id)
    WHERE status = 'PENDING';
CREATE INDEX idx_workflow_review_owner
    ON workflow_review_tasks(owner_actor_id, updated_at DESC);

CREATE TABLE evaluation_datasets (
    id              BIGSERIAL PRIMARY KEY,
    dataset_key     VARCHAR(100) NOT NULL UNIQUE,
    module_key      VARCHAR(40) NOT NULL,
    name_zh         VARCHAR(200) NOT NULL,
    name_en         VARCHAR(200) NOT NULL,
    description_zh  VARCHAR(1000),
    description_en  VARCHAR(1000),
    status          VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    owner_actor_id  VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_evaluation_dataset_module CHECK (
        module_key IN ('DATA', 'KNOWLEDGE', 'SUPPORT', 'REPORT', 'HR', 'CROSS_MODULE')
    ),
    CONSTRAINT ck_evaluation_dataset_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE evaluation_dataset_versions (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT NOT NULL REFERENCES evaluation_datasets(id) ON DELETE CASCADE,
    version_number  INTEGER NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    change_note     VARCHAR(1000),
    content_hash    VARCHAR(64),
    created_by      VARCHAR(100) NOT NULL,
    submitted_by    VARCHAR(100),
    reviewed_by     VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at    TIMESTAMPTZ,
    reviewed_at     TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    CONSTRAINT uk_evaluation_dataset_version UNIQUE (dataset_id, version_number),
    CONSTRAINT ck_evaluation_dataset_version CHECK (version_number >= 1),
    CONSTRAINT ck_evaluation_version_status CHECK (
        status IN ('DRAFT', 'IN_REVIEW', 'REVIEWED', 'PUBLISHED', 'RETIRED')
    )
);

CREATE UNIQUE INDEX uk_evaluation_one_published_version
    ON evaluation_dataset_versions(dataset_id) WHERE status = 'PUBLISHED';

CREATE TABLE evaluation_cases (
    id                    BIGSERIAL PRIMARY KEY,
    version_id            BIGINT NOT NULL REFERENCES evaluation_dataset_versions(id) ON DELETE CASCADE,
    case_key              VARCHAR(100) NOT NULL,
    title_zh              VARCHAR(300) NOT NULL,
    title_en              VARCHAR(300) NOT NULL,
    execution_type        VARCHAR(30) NOT NULL,
    prompt_key            VARCHAR(200),
    variables_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    expected_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    forbidden_json        JSONB NOT NULL DEFAULT '[]'::jsonb,
    critical              BOOLEAN NOT NULL DEFAULT FALSE,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    max_latency_ms        BIGINT,
    max_model_calls       INTEGER,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_evaluation_case_key UNIQUE (version_id, case_key),
    CONSTRAINT ck_evaluation_case_type CHECK (execution_type IN ('PROMPT', 'EXTERNAL')),
    CONSTRAINT ck_evaluation_case_prompt CHECK (
        (execution_type = 'PROMPT' AND prompt_key IS NOT NULL)
        OR execution_type = 'EXTERNAL'
    ),
    CONSTRAINT ck_evaluation_case_latency CHECK (max_latency_ms IS NULL OR max_latency_ms > 0),
    CONSTRAINT ck_evaluation_case_calls CHECK (max_model_calls IS NULL OR max_model_calls > 0)
);

CREATE INDEX idx_evaluation_cases_version
    ON evaluation_cases(version_id, enabled, case_key);

CREATE TABLE prompt_definitions (
    id                  BIGSERIAL PRIMARY KEY,
    prompt_key          VARCHAR(200) NOT NULL UNIQUE,
    module_key          VARCHAR(40) NOT NULL,
    display_name        VARCHAR(200) NOT NULL,
    description         VARCHAR(1000),
    active_version_id   BIGINT,
    previous_version_id BIGINT,
    rollout_percent     INTEGER NOT NULL DEFAULT 100,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_prompt_module CHECK (
        module_key IN ('DATA', 'KNOWLEDGE', 'SUPPORT', 'REPORT', 'HR')
    ),
    CONSTRAINT ck_prompt_rollout CHECK (rollout_percent BETWEEN 0 AND 100)
);

CREATE TABLE prompt_versions (
    id              BIGSERIAL PRIMARY KEY,
    definition_id   BIGINT NOT NULL REFERENCES prompt_definitions(id) ON DELETE CASCADE,
    version_number  INTEGER NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    content         TEXT NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,
    change_note     VARCHAR(1000),
    created_by      VARCHAR(100) NOT NULL,
    submitted_by    VARCHAR(100),
    reviewed_by     VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at    TIMESTAMPTZ,
    reviewed_at     TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    CONSTRAINT uk_prompt_version UNIQUE (definition_id, version_number),
    CONSTRAINT ck_prompt_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_prompt_version_status CHECK (
        status IN ('DRAFT', 'IN_REVIEW', 'REVIEWED', 'PUBLISHED', 'RETIRED')
    )
);

ALTER TABLE prompt_definitions
    ADD CONSTRAINT fk_prompt_active_version
        FOREIGN KEY (active_version_id) REFERENCES prompt_versions(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_prompt_previous_version
        FOREIGN KEY (previous_version_id) REFERENCES prompt_versions(id) ON DELETE SET NULL;

CREATE TABLE evaluation_runs (
    id                  UUID PRIMARY KEY,
    version_id          BIGINT NOT NULL REFERENCES evaluation_dataset_versions(id),
    prompt_version_id   BIGINT REFERENCES prompt_versions(id),
    environment         VARCHAR(30) NOT NULL,
    status              VARCHAR(30) NOT NULL,
    gate_decision       VARCHAR(30) NOT NULL DEFAULT 'NOT_VERIFIED',
    idempotency_key     VARCHAR(100) NOT NULL,
    requested_by        VARCHAR(100) NOT NULL,
    total_cases         INTEGER NOT NULL DEFAULT 0,
    passed_cases        INTEGER NOT NULL DEFAULT 0,
    failed_cases        INTEGER NOT NULL DEFAULT 0,
    not_verified_cases  INTEGER NOT NULL DEFAULT 0,
    pass_rate           NUMERIC(6,3),
    average_latency_ms  BIGINT,
    total_tokens        BIGINT,
    report_json         JSONB,
    error_category      VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    CONSTRAINT uk_evaluation_run_idempotency UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_evaluation_run_environment CHECK (
        environment IN ('LOCAL', 'MODEL', 'VENDOR', 'PRE_PRODUCTION')
    ),
    CONSTRAINT ck_evaluation_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'NOT_VERIFIED', 'CANCELED')
    ),
    CONSTRAINT ck_evaluation_gate_decision CHECK (
        gate_decision IN ('ALLOW_RELEASE', 'BLOCK_RELEASE', 'NOT_VERIFIED')
    )
);

CREATE INDEX idx_evaluation_runs_recent
    ON evaluation_runs(created_at DESC, id);
CREATE INDEX idx_evaluation_runs_version
    ON evaluation_runs(version_id, created_at DESC);

CREATE TABLE evaluation_case_results (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              UUID NOT NULL REFERENCES evaluation_runs(id) ON DELETE CASCADE,
    case_id             BIGINT NOT NULL REFERENCES evaluation_cases(id),
    status              VARCHAR(30) NOT NULL,
    output_hash         VARCHAR(64),
    output_summary      VARCHAR(1000),
    score               NUMERIC(6,3),
    latency_ms          BIGINT,
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    failure_reason      VARCHAR(1000),
    assertion_results   JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_evaluation_case_result UNIQUE (run_id, case_id),
    CONSTRAINT ck_evaluation_case_result_status CHECK (
        status IN ('PASSED', 'FAILED', 'NOT_VERIFIED')
    )
);

CREATE TABLE evaluation_gate_policies (
    module_key              VARCHAR(40) PRIMARY KEY,
    minimum_pass_rate       NUMERIC(6,3) NOT NULL,
    require_critical_pass   BOOLEAN NOT NULL DEFAULT TRUE,
    maximum_average_latency BIGINT,
    maximum_total_tokens    BIGINT,
    updated_by              VARCHAR(100) NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_evaluation_gate_module CHECK (
        module_key IN ('DATA', 'KNOWLEDGE', 'SUPPORT', 'REPORT', 'HR', 'CROSS_MODULE')
    ),
    CONSTRAINT ck_evaluation_gate_rate CHECK (minimum_pass_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_evaluation_gate_latency CHECK (
        maximum_average_latency IS NULL OR maximum_average_latency > 0
    ),
    CONSTRAINT ck_evaluation_gate_tokens CHECK (
        maximum_total_tokens IS NULL OR maximum_total_tokens > 0
    )
);

INSERT INTO evaluation_gate_policies (
    module_key, minimum_pass_rate, require_critical_pass,
    maximum_average_latency, maximum_total_tokens, updated_by
) VALUES
    ('DATA', 95, TRUE, 120000, 500000, 'system'),
    ('KNOWLEDGE', 95, TRUE, 120000, 500000, 'system'),
    ('SUPPORT', 95, TRUE, 120000, 500000, 'system'),
    ('REPORT', 95, TRUE, 120000, 500000, 'system'),
    ('HR', 100, TRUE, 120000, 500000, 'system'),
    ('CROSS_MODULE', 100, TRUE, 120000, 1000000, 'system')
ON CONFLICT (module_key) DO NOTHING;

CREATE TABLE prompt_release_audit (
    id                  BIGSERIAL PRIMARY KEY,
    definition_id       BIGINT NOT NULL REFERENCES prompt_definitions(id),
    version_id          BIGINT REFERENCES prompt_versions(id),
    action              VARCHAR(30) NOT NULL,
    actor_id            VARCHAR(100) NOT NULL,
    rollout_percent     INTEGER,
    evaluation_run_id   UUID REFERENCES evaluation_runs(id),
    note                VARCHAR(1000),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_prompt_release_action CHECK (
        action IN ('CREATED', 'UPDATED', 'SUBMITTED', 'REVIEWED', 'REJECTED',
                   'PUBLISHED', 'ROLLED_BACK')
    )
);

CREATE INDEX idx_prompt_release_audit_definition
    ON prompt_release_audit(definition_id, created_at DESC);
