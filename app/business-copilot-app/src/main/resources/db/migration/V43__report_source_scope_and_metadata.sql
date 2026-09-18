-- Keep reporting period, source definitions and administrator-selected Jira scope auditable.
ALTER TABLE report_external_connections ADD COLUMN jira_project_keys JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE report_requests ADD COLUMN period_timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai';
ALTER TABLE report_sources ADD COLUMN attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX idx_support_created_period ON support_tickets(created_at);
CREATE INDEX idx_support_reply_event_period ON support_audit_logs(created_at, ticket_id)
    WHERE event_type = 'CUSTOMER_REPLY_RECORDED';

ALTER TABLE data_report_handoffs ADD COLUMN metric_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
