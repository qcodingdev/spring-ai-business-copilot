# Changelog

All notable changes to Spring AI Business Copilot are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added a narrow `common-security` module for authenticated actors, roles, object access policies, and confirmation-token digests.
- Added Flyway V11/V12 migrations for persistent trusted-confirmation state, audit v2 metadata, and configurable audit anonymization/deletion.
- Added explicit auto-configuration for all five Copilot modules without relying on the bundled application's root package scan.
- Added PostgreSQL/MySQL Data Copilot query-target dialect detection, read-only session initialization, and integration contracts.
- Added form login with `ADMIN`, `OPERATOR`, and `REVIEWER` role boundaries and CSRF protection.
- Added request IDs and authenticated actor IDs to API responses and module audit records.
- Added an optional named, read-only Data Copilot business query datasource separate from the platform database.
- Added a Docker Compose PostgreSQL reader role that can select only the six fictional Data Copilot business tables.
- Added PostgreSQL/pgvector Testcontainers coverage and a GitHub Actions Maven verification workflow.
- Added v1.0.0-to-v1.1 database upgrade coverage and a frontend JavaScript syntax gate.
- Added a health, CSRF login, and authenticated-workbench smoke-test script.
- Added a repeatable real-model release smoke test covering all five Copilot workflows.
- Exposed authenticated Actuator metrics so Spring AI model latency and provider-reported token usage can be inspected.

### Changed

- Persisted Data SQL candidates and bound Data/Support/Report/Resume confirmation state to owner, object status, expiry, and conditional database transitions.
- Replaced Resume Mapper scanning with an explicit Spring JDBC repository.
- Expanded AI and business audit records with creator/action actors, provider/model, Prompt identity, policy, latency, token usage, finish reason, and provider request IDs.
- Pinned Maven lifecycle plugin versions for reproducible local and CI builds.
- Added a persistent BuildKit Maven cache and a focused Docker build context for faster, retryable image builds.
- Removed fixed Compose container names and made host ports configurable so isolated project stacks do not collide.
- Made deployment smoke tests wait for application health to avoid container-startup races.
- Made Data Copilot schema metadata and prompts use schema-qualified table names.

### Fixed

- Corrected pgvector retrieval parameter ordering so similarity thresholds and result limits are applied as intended.
- Made Data Copilot SQL confirmation tokens atomic and single-use.
- Made Support Copilot confirm/cancel tokens expiry-aware, atomic, and single-use.
- Preserved Knowledge Copilot uploads when embeddings are unavailable, kept unindexed documents disabled, and added a reindex recovery flow.
- Kept the platform datasource and JDBC template as the default candidates when the optional Data Copilot query datasource is enabled.
- Made the Maven launcher prefer the optional project-local JDK while accepting a CI-provided Java 21 `JAVA_HOME`.

### Security

- Enforced fully-qualified SQL column allowlists, rejected `SELECT *`/`table.*`, and normalized PostgreSQL/MySQL quoted identifiers before policy checks.
- Made high-risk Data, Support, Report, and Resume state transitions fail closed when required audit persistence fails.
- Removed the implicit non-request `system/ADMIN` actor fallback; background hosts must provide an explicit controlled actor provider.
- Denied unlisted API HTTP methods by default and added a production profile that requires explicit database and role credentials.
- Enforced exact schema-qualified SQL table allowlists, including nested subqueries and quoted identifiers.
- Denied database functions by default and explicitly allowed only `count`, `sum`, `avg`, `min`, and `max`.
- Required bounded integer-literal `LIMIT` values and added JDBC timeout, row, fetch-size, column, and result-byte caps.
- Added Flyway V10 and PostgreSQL integration tests that prevent the example reader from accessing audit or other Copilot tables and from executing DML/DDL.

## [1.0.0] - 2026-07-12

### Added

- Data Copilot for confirmed, read-only Text to SQL queries with masking and audit logs.
- Knowledge Copilot for document ingestion, vector retrieval, citations, and no-evidence refusal.
- Support Copilot for ticket classification, evidence-grounded reply drafts, and human confirmation.
- Report Copilot for source-grounded weekly reports, review states, and Markdown export.
- Resume Copilot for privacy-first, criterion-by-criterion resume evidence review without ranking or hiring decisions.
- Shared Spring AI 2.0 model, prompt, guardrail, audit, and web infrastructure.
- PostgreSQL and pgvector schema migrations, fictional sample data, Docker Compose, and a unified web workbench.
- English and Simplified Chinese project documentation, module guides, security policy, contribution guide, and workbench demo.

### Changed

- Aligned the Maven reactor and executable application artifact with the `1.0.0` release.
- Declared the MIT License consistently in the repository, README files, and Maven metadata.
- Separated chat and embedding model endpoint configuration for OpenAI-compatible providers.

### Security

- Added deterministic SQL, citation, report, support, privacy, evidence, and hiring guardrails.
- Kept risky actions behind single-use confirmation tokens and explicit human review.
- Excluded local AI-agent settings, internal planning documents, and generated review evidence from the public repository.

[Unreleased]: https://github.com/qcodingdev/spring-ai-business-copilot/compare/v1.0.0...main
[1.0.0]: https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v1.0.0
