# Changelog

All notable changes to Spring AI Business Copilot are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-07-15

### Added

- Added form login with `ADMIN`, `OPERATOR`, and `REVIEWER` role boundaries and CSRF protection.
- Added request IDs and authenticated actor IDs to API responses and module audit records.
- Added an optional named, read-only Data Copilot business query datasource separate from the platform database.
- Added a Docker Compose PostgreSQL read-only role example with default `SELECT` grants for Data Copilot.
- Added PostgreSQL/pgvector Testcontainers coverage and a GitHub Actions Maven verification workflow.
- Added a health, CSRF login, and authenticated-workbench smoke-test script.
- Exposed authenticated Actuator metrics so Spring AI model latency and provider-reported token usage can be inspected.

### Changed

- Pinned Maven lifecycle plugin versions for reproducible local and CI builds.
- Added a persistent BuildKit Maven cache and a focused Docker build context for faster, retryable image builds.
- Removed fixed Compose container names and made host ports configurable so isolated project stacks do not collide.

### Fixed

- Corrected pgvector retrieval parameter ordering so similarity thresholds and result limits are applied as intended.
- Made Data Copilot SQL confirmation tokens atomic and single-use.
- Made Support Copilot confirm/cancel tokens expiry-aware, atomic, and single-use.
- Preserved Knowledge Copilot uploads when embeddings are unavailable, kept unindexed documents disabled, and added a reindex recovery flow.
- Kept the platform datasource and JDBC template as the default candidates when the optional Data Copilot query datasource is enabled.

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

[Unreleased]: https://github.com/qcodingdev/spring-ai-business-copilot/compare/v1.1.0...main
[1.1.0]: https://github.com/qcodingdev/spring-ai-business-copilot/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v1.0.0
