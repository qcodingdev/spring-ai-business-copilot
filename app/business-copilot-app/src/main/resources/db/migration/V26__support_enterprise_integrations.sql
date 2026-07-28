-- V26：Support Copilot 外部工单连接、只读业务上下文、SLA 和受控草稿回写。

CREATE TABLE support_external_connections (
    id                  BIGSERIAL PRIMARY KEY,
    connection_key      VARCHAR(100) NOT NULL UNIQUE,
    display_name        VARCHAR(200) NOT NULL,
    provider            VARCHAR(40) NOT NULL,
    base_url            VARCHAR(500) NOT NULL,
    secret_ref          VARCHAR(200) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    owner_actor_id      VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_external_provider CHECK (provider IN
        ('JIRA_SERVICE_MANAGEMENT', 'ZENDESK', 'SERVICENOW', 'FEISHU', 'WECOM'))
);

CREATE TABLE support_ticket_context_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    ticket_id           BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    context_type        VARCHAR(32) NOT NULL,
    source_reference    VARCHAR(300) NOT NULL,
    sanitized_payload   JSONB NOT NULL,
    observed_at         TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_context_type CHECK (context_type IN
        ('CUSTOMER', 'ORDER', 'SERVICE_STATUS'))
);

CREATE INDEX idx_support_ticket_context_ticket
    ON support_ticket_context_snapshots(ticket_id, context_type, observed_at DESC);

ALTER TABLE support_tickets
    ADD COLUMN IF NOT EXISTS external_connection_id BIGINT
        REFERENCES support_external_connections(id),
    ADD COLUMN IF NOT EXISTS external_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sla_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sla_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CONFIGURED';

ALTER TABLE support_tickets
    ADD CONSTRAINT ck_support_ticket_sla_status CHECK (sla_status IN
        ('NOT_CONFIGURED', 'ON_TRACK', 'AT_RISK', 'BREACHED', 'PAUSED'));

CREATE TABLE support_draft_writebacks (
    id                  BIGSERIAL PRIMARY KEY,
    draft_id            BIGINT NOT NULL REFERENCES support_reply_drafts(id) ON DELETE CASCADE,
    connection_id       BIGINT NOT NULL REFERENCES support_external_connections(id),
    external_ticket_id  VARCHAR(300) NOT NULL,
    payload_hash        VARCHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    token_digest        VARCHAR(64),
    expires_at          TIMESTAMPTZ,
    requested_by        VARCHAR(100) NOT NULL,
    confirmed_by        VARCHAR(100),
    completed_at        TIMESTAMPTZ,
    error_category      VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_support_draft_writeback UNIQUE (draft_id, connection_id, external_ticket_id),
    CONSTRAINT ck_support_writeback_status CHECK (status IN
        ('PENDING_CONFIRMATION', 'CONFIRMED', 'COMPLETED', 'FAILED', 'EXPIRED', 'CANCELED'))
);

CREATE INDEX idx_support_writebacks_status
    ON support_draft_writebacks(status, created_at DESC);
