-- V34: lightweight task runtime — runs, steps and attempts (RUN-01 ~ RUN-07).

CREATE TABLE task_runs (
    run_id                   VARCHAR(64) PRIMARY KEY,
    module_name              VARCHAR(50)  NOT NULL,
    ref_type                 VARCHAR(50)  NOT NULL,
    ref_id                   VARCHAR(100) NOT NULL,
    owner_actor_id           VARCHAR(100) NOT NULL,
    status                   VARCHAR(30)  NOT NULL,
    max_model_calls          INTEGER,
    max_tool_calls           INTEGER,
    max_tokens               INTEGER,
    max_duration_seconds     BIGINT,
    context_auth_source      VARCHAR(200),
    context_retention_seconds BIGINT,
    context_expires_at       TIMESTAMPTZ,
    started_at               TIMESTAMPTZ  NOT NULL,
    ended_at                 TIMESTAMPTZ,
    failure_category         VARCHAR(30),
    stop_reason              VARCHAR(500),
    confirmed_by_actor_id    VARCHAR(100),
    CONSTRAINT ck_task_runs_status CHECK (status IN (
        'RUNNING', 'WAITING_CONFIRMATION', 'SUCCEEDED', 'FAILED',
        'CANCELLED', 'BUDGET_EXHAUSTED', 'OUTCOME_UNKNOWN'))
);

CREATE INDEX idx_task_runs_status ON task_runs (status);
CREATE INDEX idx_task_runs_ref ON task_runs (ref_type, ref_id);
CREATE INDEX idx_task_runs_owner ON task_runs (owner_actor_id);

CREATE TABLE task_steps (
    step_id          VARCHAR(64) PRIMARY KEY,
    run_id           VARCHAR(64) NOT NULL REFERENCES task_runs (run_id) ON DELETE CASCADE,
    step_name        VARCHAR(100) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    failure_category VARCHAR(30),
    evidence_refs    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    summary          VARCHAR(500),
    started_at       TIMESTAMPTZ  NOT NULL,
    ended_at         TIMESTAMPTZ,
    CONSTRAINT ck_task_steps_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_task_steps_run ON task_steps (run_id);

CREATE TABLE task_attempts (
    attempt_id       VARCHAR(64) PRIMARY KEY,
    run_id           VARCHAR(64) NOT NULL REFERENCES task_runs (run_id) ON DELETE CASCADE,
    step_id          VARCHAR(64) NOT NULL REFERENCES task_steps (step_id) ON DELETE CASCADE,
    kind             VARCHAR(10) NOT NULL,
    operation        VARCHAR(100) NOT NULL,
    provider_name    VARCHAR(100),
    model_name       VARCHAR(100),
    input_tokens     INTEGER,
    output_tokens    INTEGER,
    latency_ms       BIGINT       NOT NULL DEFAULT 0,
    outcome          VARCHAR(10) NOT NULL,
    failure_category VARCHAR(30),
    occurred_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_task_attempts_kind CHECK (kind IN ('MODEL', 'TOOL')),
    CONSTRAINT ck_task_attempts_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'UNKNOWN'))
);

CREATE INDEX idx_task_attempts_run ON task_attempts (run_id);
CREATE INDEX idx_task_attempts_step ON task_attempts (step_id);
