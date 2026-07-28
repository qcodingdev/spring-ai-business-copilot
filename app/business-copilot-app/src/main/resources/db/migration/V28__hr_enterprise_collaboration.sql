-- V28：HR Copilot 候选人授权、结构化题库、面试协作、ATS 只读导入和入职清单。

CREATE TABLE hr_candidate_consents (
    id                  BIGSERIAL PRIMARY KEY,
    consent_reference   VARCHAR(200) NOT NULL UNIQUE,
    candidate_reference VARCHAR(200) NOT NULL,
    purpose             VARCHAR(300) NOT NULL,
    granted_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    recorded_by         VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_hr_candidate_consent_period CHECK (expires_at > granted_at)
);

ALTER TABLE resume_submissions
    ADD COLUMN IF NOT EXISTS consent_id BIGINT REFERENCES hr_candidate_consents(id),
    ADD COLUMN IF NOT EXISTS candidate_reference VARCHAR(200);

CREATE TABLE hr_interview_question_bank (
    id                  BIGSERIAL PRIMARY KEY,
    question_key        VARCHAR(100) NOT NULL,
    version             BIGINT NOT NULL DEFAULT 1,
    category            VARCHAR(80) NOT NULL,
    question_text       VARCHAR(1000) NOT NULL,
    evidence_guidance   VARCHAR(1500) NOT NULL,
    prohibited_topics   JSONB NOT NULL DEFAULT '[]'::jsonb,
    active              BOOLEAN NOT NULL DEFAULT FALSE,
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    owner_actor_id      VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_hr_question_bank_version UNIQUE (question_key, version)
);

CREATE TABLE hr_interview_sessions (
    id                  BIGSERIAL PRIMARY KEY,
    assessment_id       BIGINT NOT NULL REFERENCES resume_assessments(id) ON DELETE CASCADE,
    session_reference   VARCHAR(100) NOT NULL UNIQUE,
    status              VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    owner_actor_id      VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ,
    CONSTRAINT ck_hr_interview_session_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELED'))
);

CREATE TABLE hr_interview_opinions (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT NOT NULL REFERENCES hr_interview_sessions(id) ON DELETE CASCADE,
    interviewer_actor_id VARCHAR(100) NOT NULL,
    evidence_json       JSONB NOT NULL,
    gaps_json           JSONB NOT NULL DEFAULT '[]'::jsonb,
    opinion_text        VARCHAR(4000) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_hr_interview_opinion_actor UNIQUE (session_id, interviewer_actor_id)
);

CREATE TABLE hr_ats_connections (
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
    CONSTRAINT ck_hr_ats_provider CHECK (provider IN
        ('WORKDAY', 'GREENHOUSE', 'LEVER', 'MOKA', 'BEISEN', 'GENERIC_READ_ONLY'))
);

CREATE TABLE hr_ats_imports (
    id                  BIGSERIAL PRIMARY KEY,
    connection_id       BIGINT NOT NULL REFERENCES hr_ats_connections(id),
    external_candidate_id VARCHAR(300) NOT NULL,
    consent_reference   VARCHAR(200) NOT NULL,
    sanitized_payload   JSONB NOT NULL,
    source_updated_at   TIMESTAMPTZ,
    imported_by         VARCHAR(100) NOT NULL,
    imported_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_hr_ats_candidate UNIQUE (connection_id, external_candidate_id)
);

CREATE TABLE hr_onboarding_checklists (
    id                  BIGSERIAL PRIMARY KEY,
    checklist_key       VARCHAR(100) NOT NULL,
    version             BIGINT NOT NULL DEFAULT 1,
    title               VARCHAR(300) NOT NULL,
    role_scope          VARCHAR(100),
    items_json          JSONB NOT NULL,
    knowledge_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    active              BOOLEAN NOT NULL DEFAULT FALSE,
    owner_actor_id      VARCHAR(100) NOT NULL,
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_hr_onboarding_checklist_version UNIQUE (checklist_key, version)
);
