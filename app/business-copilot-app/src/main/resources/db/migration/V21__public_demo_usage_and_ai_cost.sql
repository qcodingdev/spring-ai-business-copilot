-- V21：不保存原始输入和原始 IP 的每日额度与 AI 用量聚合。

CREATE TABLE IF NOT EXISTS public_demo_usage_daily (
    usage_date          DATE NOT NULL,
    client_fingerprint  VARCHAR(64) NOT NULL,
    ai_operations       INTEGER NOT NULL DEFAULT 0,
    external_model_calls INTEGER NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (usage_date, client_fingerprint)
);

CREATE TABLE IF NOT EXISTS ai_usage_daily (
    usage_date       DATE NOT NULL,
    provider_name    VARCHAR(80) NOT NULL,
    model_name       VARCHAR(160) NOT NULL,
    call_type        VARCHAR(20) NOT NULL,
    operation        VARCHAR(80) NOT NULL,
    calls            BIGINT NOT NULL DEFAULT 0,
    successes        BIGINT NOT NULL DEFAULT 0,
    failures         BIGINT NOT NULL DEFAULT 0,
    input_tokens     BIGINT NOT NULL DEFAULT 0,
    output_tokens    BIGINT NOT NULL DEFAULT 0,
    total_latency_ms BIGINT NOT NULL DEFAULT 0,
    estimated_cost   NUMERIC(18, 8),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (usage_date, provider_name, model_name, call_type, operation)
);

CREATE INDEX IF NOT EXISTS idx_public_demo_usage_updated_at
    ON public_demo_usage_daily(updated_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_daily_date
    ON ai_usage_daily(usage_date DESC);
