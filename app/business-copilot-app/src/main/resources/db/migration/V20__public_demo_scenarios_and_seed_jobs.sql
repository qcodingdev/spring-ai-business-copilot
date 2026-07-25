-- V20：长期公网体验所需的服务端场景目录、预生成结果和可恢复初始化任务。

CREATE TABLE IF NOT EXISTS demo_scenarios (
    scenario_id               VARCHAR(100) PRIMARY KEY,
    module                    VARCHAR(20) NOT NULL,
    title                     VARCHAR(200) NOT NULL,
    description               VARCHAR(600) NOT NULL,
    input_template            TEXT NOT NULL,
    allowed_operations        TEXT NOT NULL,
    data_scope_json           TEXT NOT NULL,
    data_scope_label          VARCHAR(500) NOT NULL,
    version                   INTEGER NOT NULL,
    enabled                   BOOLEAN NOT NULL DEFAULT TRUE,
    system_managed            BOOLEAN NOT NULL DEFAULT TRUE,
    fallback_result_available BOOLEAN NOT NULL DEFAULT FALSE,
    content_hash              VARCHAR(64) NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE demo_scenarios DROP CONSTRAINT IF EXISTS ck_demo_scenario_module;
ALTER TABLE demo_scenarios ADD CONSTRAINT ck_demo_scenario_module
    CHECK (module IN ('KNOWLEDGE', 'SUPPORT', 'HR', 'DATA', 'REPORT'));
CREATE INDEX IF NOT EXISTS idx_demo_scenarios_module_enabled
    ON demo_scenarios(module, enabled, scenario_id);

CREATE TABLE IF NOT EXISTS demo_scenario_results (
    scenario_id      VARCHAR(100) PRIMARY KEY REFERENCES demo_scenarios(scenario_id) ON DELETE CASCADE,
    scenario_version INTEGER NOT NULL,
    result_json      TEXT NOT NULL,
    generated_at     TIMESTAMPTZ NOT NULL,
    reviewed_at      TIMESTAMPTZ,
    content_hash     VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS demo_data_jobs (
    id                UUID PRIMARY KEY,
    job_type          VARCHAR(20) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    requested_by      VARCHAR(100) NOT NULL,
    summary_json      TEXT,
    error_category    VARCHAR(80),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE demo_data_jobs DROP CONSTRAINT IF EXISTS ck_demo_data_job_type;
ALTER TABLE demo_data_jobs ADD CONSTRAINT ck_demo_data_job_type
    CHECK (job_type IN ('INITIALIZE', 'RESET'));
ALTER TABLE demo_data_jobs DROP CONSTRAINT IF EXISTS ck_demo_data_job_status;
ALTER TABLE demo_data_jobs ADD CONSTRAINT ck_demo_data_job_status
    CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'));
CREATE INDEX IF NOT EXISTS idx_demo_data_jobs_created_at
    ON demo_data_jobs(created_at DESC);

CREATE TABLE IF NOT EXISTS demo_reset_intents (
    token_digest       VARCHAR(64) PRIMARY KEY,
    requested_by       VARCHAR(100) NOT NULL,
    deletion_counts    TEXT NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS demo_admin_audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    actor_id        VARCHAR(100) NOT NULL,
    action          VARCHAR(50) NOT NULL,
    scope_summary   TEXT,
    affected_count  INTEGER NOT NULL DEFAULT 0,
    result          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_demo_admin_audit_created_at
    ON demo_admin_audit_logs(created_at DESC);

ALTER TABLE support_tickets
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scenario_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_support_queue_filters
    ON support_tickets(status, category, urgency, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_system_managed
    ON support_tickets(system_managed, owner_actor_id);

ALTER TABLE report_requests
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scenario_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_report_requests_system_managed
    ON report_requests(system_managed, owner_actor_id);

ALTER TABLE resume_jobs
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scenario_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_resume_jobs_system_managed
    ON resume_jobs(system_managed, owner_actor_id);

ALTER TABLE resume_submissions
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scenario_id VARCHAR(100);

ALTER TABLE customers ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE products ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE marketing_events ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE customers SET system_managed = TRUE WHERE id >= 1000;
UPDATE products SET system_managed = TRUE WHERE id >= 1000;
UPDATE orders SET system_managed = TRUE WHERE id >= 1000;
UPDATE order_items SET system_managed = TRUE WHERE id >= 1000;
UPDATE refunds SET system_managed = TRUE WHERE id >= 1000;
UPDATE marketing_events SET system_managed = TRUE WHERE id >= 1000;
