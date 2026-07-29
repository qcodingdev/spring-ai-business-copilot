# Contributing

Thank you for helping improve Spring AI Business Copilot.

## Development workflow

1. Use Java 21 and run `./mvnw -q test` before submitting a change.
2. Keep changes inside the owning module; shared code must be used by a real workflow.
3. Put prompts under `platform/ai-core/src/main/resources/prompts/<copilot>/`.
4. Add deterministic guardrails before model output changes business state.
5. Use Flyway for DDL. Use MyBatis-Plus only for stable CRUD and JDBC for dynamic or batch-specific access.
6. Update the root and module README when behavior or public APIs change.

## Data and security

- Use only fictional, sanitized data in code, tests, screenshots, issues, and pull requests.
- Never commit API keys, production URLs, customer data, internal documents, or real resumes.
- Do not add automatic sending, publishing, hiring decisions, or arbitrary model-generated tool execution without an explicit design review.

## Pull requests

Describe the business problem, behavioral change, guardrails, tests, and remaining limitations. Keep unrelated refactors out of the same change.

Only maintainers publish formal releases after the required automated, database, browser, and security gates pass. Passing local tests alone does not authorize a release.
