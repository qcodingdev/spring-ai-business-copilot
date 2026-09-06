-- V35: layered acceptance evidence — runtime readiness, model quality,
-- vendor acceptance and release gate recorded separately (CORE-05).

CREATE TABLE acceptance_evidence (
    id                  BIGSERIAL PRIMARY KEY,
    category            VARCHAR(30)  NOT NULL,
    name                VARCHAR(100) NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    source              VARCHAR(200) NOT NULL,
    applicable_version  VARCHAR(100),
    note                VARCHAR(1000),
    recorded_by         VARCHAR(100) NOT NULL,
    recorded_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_acceptance_category CHECK (category IN (
        'RUNTIME_READINESS', 'MODEL_QUALITY', 'VENDOR_ACCEPTANCE', 'RELEASE_GATE')),
    CONSTRAINT ck_acceptance_status CHECK (status IN (
        'PASS', 'ATTENTION', 'FAILED', 'NOT_VERIFIED')),
    CONSTRAINT uq_acceptance_category_name UNIQUE (category, name)
);

CREATE INDEX idx_acceptance_category ON acceptance_evidence (category);
