# Changelog

All notable changes to Spring AI Business Copilot are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://gitee.com/qcodingdev/spring-ai-business-copilot/compare/v1.0.0...master
[1.0.0]: https://gitee.com/qcodingdev/spring-ai-business-copilot/releases/tag/v1.0.0
