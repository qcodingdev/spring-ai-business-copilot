# Changelog

All notable changes to Spring AI Business Copilot are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.2.1] - 2026-07-29

### Changed

- Refreshed the bilingual README and runnable-application visuals for the `2.2.1` release.
- Removed internal planning, audit, architecture-decision, release-process, and local visual-generation files from the public repository.

### Security

- Upgraded Bouncy Castle to 1.84 to remediate CVE-2026-0636 while retaining the earlier CVE-2025-14813 fix.

## [2.2.0] - 2026-07-29

### Added

- Completed the `2.2.0` enterprise quality loop with stable Knowledge answer IDs, owner-bound idempotent helpful/not-helpful feedback, and an Admin/Reviewer quality review queue.
- Added Flyway V22 for constrained Knowledge answer feedback and workbench controls that keep users in the current answer flow with an explicit reviewer next step.
- Added Flyway V23, concurrency-safe Knowledge quality dispositions, automatic re-queue after newer feedback, and low-cardinality quality metrics.
- Added Flyway V24-V28 and enterprise APIs for Data governance/export/Report handoff, Knowledge source sync, Support ticket/SLA/writeback integration, Report schedules/office exports, and HR consent/interview/ATS/onboarding collaboration.
- Added bounded XLSX/HTML extraction, mounted-drive and S3/MinIO sources, and REST adapters for SharePoint, Confluence, Notion, Jira Service Management, Zendesk, ServiceNow, Feishu, WeCom, and common ATS providers.
- Added environment-reference-only external credentials, configurable Data query-plan row budgets, active JDBC cancellation, and private Admin enterprise status counts.
- Added fixed low-cardinality AI call metrics, call IDs, operation tags, and Chinese request-to-model chain logs across all five Copilots.
- Added bounded AI concurrency, separate Chat/Embedding circuit breakers, explicit provider timeouts, and restricted transient-failure retries.
- Expanded the five fixed evaluation datasets to 67 cases and added a CI dataset-size gate, including end-to-end Data SQL safety cases.
- Added authenticated capacity smoke testing, non-overwriting PostgreSQL backups, and disposable-container restore drills.

### Fixed

- Extended Resume protected-attribute detection to reject Chinese gender and marital-status requirements and common English age or gender restrictions.
- Masked external Support ticket messages before persistence and made Data query-cost previews fail closed when database estimates cannot be recognized.
- Kept Support knowledge retrieval inside the authenticated ACL boundary while falling back across document categories when a category-specific lookup has no evidence.
- Made HR job drafts preserve recruiter-provided required and preferred qualifications deterministically and reject model-added qualifications.
- Corrected immutable AI property binding and made Admin diagnostics distinguish provider-omitted token usage from zero usage.

### Changed

- Updated both README files, the six-frame workbench GIF, the social preview, and the 2.2 enterprise diagnostics screenshot from a running local application.
- Added five-module enterprise scenario tests plus real PostgreSQL constraint coverage for Data, Knowledge, Support, Report, and HR boundaries.

### Security

- Bound Knowledge feedback to the authenticated creator of the persisted answer audit and restricted the cross-user quality queue to Admin/Reviewer roles.
- Masked phone numbers, email addresses, ID cards, and credential assignments in feedback comments and reviewer notes before persistence.
- Restricted Actuator metrics to Admin and Reviewer roles and kept user input, Prompt text, and file names out of metric labels and AI operation logs.
- Upgraded the MinIO Java client to 8.6.0 for CVE-2025-59952, pinned Bouncy Castle 1.81.1 for CVE-2025-14813, and selected OkHttp JVM 5.1.0 explicitly for Maven runtime compatibility.

## [2.0.0] - 2026-07-18

### Added

- Added Flyway V13-V16 migrations for versioned Knowledge documents, durable index jobs, Support state and evidence lifecycle, immutable Report source snapshots, and versioned Resume criteria/retention/review outcomes.
- Added bounded shared TXT/Markdown/PDF/DOCX extraction through `platform/document-processing`.
- Added Knowledge hybrid text/vector retrieval, retryable asynchronous indexing, exact citation excerpt validation, and citation quality metrics.
- Added Support draft editing, feedback, decision outcomes, explicit ticket/draft state enums, and versioned knowledge evidence binding.
- Added bounded Report CSV/JSON source import, freshness metadata, immutable snapshots, and deterministic escaped HTML export.
- Added Resume JD/resume file ingestion, criteria versioning, reviewer corrections and feedback, review workbench reads, and automatic/manual sanitized-submission deletion.
- Added fixed evaluation datasets for Knowledge citations, Support reply guardrails, Report grounding, and Resume hiring compliance.
- Added CycloneDX SBOM generation, MySQL 5.7/8.4 compatibility jobs, dependency review, repository/container Trivy gates, and a non-root read-only runtime container.
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

- Upgraded every Maven module to the `2.0.0` release line.
- Upgraded all module Prompt and policy identities to v2.0 so audits can distinguish the new behavior.
- Made the unified workbench expose document uploads, report file import and HTML export, resume reviewer correction/feedback, and sanitized-data deletion.
- Made the public login form expand only after an explicit login action, linked the authenticated brand back to the default workbench home, and moved the viewport to newly generated results.
- Refreshed the bilingual root README and workbench demo animation for the redesigned public landing page and authenticated five-module workspace.
- Made Resume criteria and assessment prompts produce Simplified Chinese user-facing content by default, with a server-side language guardrail until explicit internationalization is introduced.
- Replaced duplicated Support JSON and module-local English Resume samples with the canonical workbench examples, and removed superseded v1 planning and implementation-prompt documents.
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
- Preserved Knowledge Copilot uploads when embeddings are unavailable, completed them as text-search indexes, added bounded Chinese keyword retrieval, and recovered earlier `MODEL_DISABLED` jobs.
- Ensured Support Copilot is configured after Knowledge Copilot, distinguishes missing evidence from high-risk human review, and includes low-risk knowledge-backed examples.
- Kept the platform datasource and JDBC template as the default candidates when the optional Data Copilot query datasource is enabled.
- Made the Maven launcher prefer the optional project-local JDK while accepting a CI-provided Java 21 `JAVA_HOME`.

### Security

- Bound Knowledge document access, durable jobs, Support decisions, Report snapshots, and Resume reviews to authenticated owners/reviewers and conditional state transitions.
- Revalidated reviewer-edited Support/Resume content before accepting it as a business outcome.
- Added bounded document/file parsing, encrypted-PDF rejection, source freshness checks, retention cleanup, and immutable report evidence.
- Made the application container run as UID/GID `10001`, drop Linux capabilities in Compose, use a read-only root filesystem, and enforce `no-new-privileges`.
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

[Unreleased]: https://github.com/qcodingdev/spring-ai-business-copilot/compare/v2.2.0...main
[2.2.0]: https://github.com/qcodingdev/spring-ai-business-copilot/compare/v2.0.0...v2.2.0
[2.0.0]: https://github.com/qcodingdev/spring-ai-business-copilot/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v1.0.0
