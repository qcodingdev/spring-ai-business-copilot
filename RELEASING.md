# Releasing

This repository keeps development work off `master`/`main` until a version has passed its release gates and the maintainer explicitly approves the release.

## Version lifecycle

1. Develop on a semantic feature branch such as `feature/v2.3-knowledge-sync` with a `2.3.0-SNAPSHOT` Maven version.
2. Keep pending release notes under `Unreleased` in `CHANGELOG.md`.
3. Run the automated, database, deployment, frontend, and AI quality gates below.
4. Obtain explicit maintainer confirmation that the version is complete and may be formally released.
5. On the feature branch, remove `-SNAPSHOT`, finalize the dated Changelog section, and create the release commit.
6. Merge the approved release through a pull request into the canonical main branch; prefer a fast-forward-compatible linear history.
7. Tag the resulting main-branch commit as `v2.x.x`, push the branch and tag, and publish matching GitHub/Gitee release notes.
8. Delete merged feature, snapshot, and tag-named branches after verifying that both remotes contain the main commit and release tag.
9. Start the next version on a new semantic feature branch using the next `2.x.x-SNAPSHOT` Maven version.

Formal versions use `2.x.x` in Maven and `v2.x.x` for the immutable Git tag and Release. Do not create long-lived branches named `v2.x.x`; keeping both a branch and tag with the same name makes Git references ambiguous. Long-term history is retained by the canonical main branch plus release tags.

Do not merge, tag, push, or publish merely because local tests pass. Release approval is a separate, explicit decision.

## Required gates

- `./mvnw --batch-mode --no-transfer-progress clean verify`
- PostgreSQL/pgvector Testcontainers, including empty-database and previous-version upgrade migration paths
- `./scripts/check-frontend-syntax.sh`
- `docker compose up --build` plus `./scripts/smoke-test.sh`
- Database privilege inspection proving the business query role has `SELECT` but no DDL/DML privileges
- With real chat and embedding model credentials: `./scripts/release-ai-smoke-test.sh`
- README, module documentation, environment variables, migration notes, and Changelog review
- No committed credentials, customer data, internal documents, or real resumes

The real-model smoke test creates only fictional release-validation records and never sends external messages, publishes reports, or changes hiring decisions.
