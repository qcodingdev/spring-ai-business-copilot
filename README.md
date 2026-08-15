<h1 align="center">Spring AI Business Copilot</h1>

<p align="center">
  <strong>An open-source AI operations workbench for real enterprise workflows.</strong><br>
  Governed data analysis · Enterprise knowledge · Customer operations · Business reporting · Recruiting and employee services
</p>

<p align="center">
  <a href="https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v2.3.1"><img alt="Release v2.3.1" src="https://img.shields.io/badge/Release-v2.3.1-2563EB"></a>
  <a href="https://openjdk.org/projects/jdk/21/"><img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&amp;logoColor=white"></a>
  <a href="https://spring.io/projects/spring-boot"><img alt="Spring Boot 4.1" src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&amp;logoColor=white"></a>
  <a href="https://spring.io/projects/spring-ai"><img alt="Spring AI 2.0" src="https://img.shields.io/badge/Spring%20AI-2.0-6DB33F"></a>
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/License-MIT-blue"></a>
</p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#current-business-capabilities">Capabilities</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v2.3.1">Stable v2.3.1</a> ·
  <a href="https://gitee.com/qcodingdev/spring-ai-business-copilot">Gitee</a>
</p>

![Spring AI Business Copilot 2.3 workbench](assets/workbench-demo.gif)

> **Stable release:** `v2.3.1` retains the five governed enterprise workflows and hardens external integrations. It retrieves complete paginated and nested Notion page content, adds deterministic provider-contract coverage, and runs recurring dependency and container security checks. Production deployment still requires deployment-owned identity, secrets, network policy, retention settings, and vendor sandbox acceptance.

## One workbench for five enterprise domains

Spring AI Business Copilot has grown from the original Data Copilot into a unified workbench for data analysis, enterprise knowledge, customer operations, business reporting, recruiting, and employee services. Each domain can run independently, while data handoffs, grounded knowledge, human review, and persisted state connect them into operational workflows.

The `2.3` line productizes these existing capabilities rather than adding more modules. `2.3.1` strengthens the external-integration and maintenance baseline:

- **Unified enterprise workbench:** a bilingual Vue 3 + TypeScript interface brings together the overview, five business domains, and system administration, with actions scoped to `ADMIN`, `OPERATOR`, and `REVIEWER` roles.
- **Cross-domain collaboration:** Data results flow into Report; Knowledge grounds support and employee-policy answers; external tickets, knowledge sources, report inputs, and ATS records enter controlled module workflows.
- **Complete human review:** SQL execution, knowledge-quality disposition, support drafts, report confirmation, and recruiting assessments retain evidence, risk, state, human edits, and confirmation records.
- **Diagnosable delivery:** administration covers runtime health, AI call chains, token/latency visibility, knowledge documents, and experience data; Docker Compose, automated tests, SBOM, and security gates cover delivery.
- **Maintained integrations:** Notion uses the current `2026-03-11` API contract and bounded full-page traversal; direct contracts cover SharePoint, Confluence, Notion, Jira Service Management, Zendesk, ServiceNow, Feishu, and WeCom.

## Current business capabilities

| Domain | Operational flow available today | Key control |
|---|---|---|
| [Data analysis](modules/data-copilot/README.md) | Generate SQL candidates from natural language; govern metric definitions and approved templates; inspect result snapshots and audits; hand masked results to Report | Queries are read-only and bounded by schema, columns, functions, rows, time, and result size, with confirmation before execution |
| [Enterprise knowledge](modules/knowledge-copilot/README.md) | Upload and manage documents; synchronize governed sources; ask cited questions; process a quality queue with separate evidence, answer, remediation, and disposition fields | The system refuses to answer without current accessible evidence, and every citation resolves to the current document version |
| [Customer operations](modules/support-copilot/README.md) | Analyze tickets with SLA and similar-case context; revise and confirm drafts in a human-review queue; manage external connections and outcome records | Confirming a draft does not send a customer message; external internal-note writeback requires a separate preview and confirmation |
| [Business reporting](modules/report-copilot/README.md) | Start from a Data handoff that fills title and source automatically, or use typed/CSV/JSON input; generate, edit, confirm, schedule, and export reports | Facts remain bound to immutable source snapshots; schedules create reviewable drafts and never auto-publish |
| [Recruiting and employee services](modules/resume-copilot/README.md) | Recruiting covers job criteria, evidence-based resume review, interviews, candidate consent, and read-only ATS import; employee services cover cited policy Q&A and onboarding checklists | No score, ranking, hire/reject conclusion, or ATS write action is produced |

## Quick start

Requirements: Docker with Compose support.

```bash
git clone --branch v2.3.1 --single-branch \
  https://github.com/qcodingdev/spring-ai-business-copilot.git
cd spring-ai-business-copilot/examples
cp .env.example .env
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080), select **Log in to try**, and sign in with `admin / admin-change-me`.

| Mode | What works |
|---|---|
| No model key | Product navigation, roles, fictional records, governance screens, and deterministic non-AI paths |
| Chat model configured | Data, Support, Report, and HR model-backed generation flows |
| Chat + embedding configured | Full Knowledge ingestion, semantic retrieval, and cited Q&A |

> The bundled credentials and data are for local evaluation only. Replace every `BUSINESS_COPILOT_*` password before using a shared environment, and never paste real customer data, internal documents, credentials, or resumes into a demo deployment.

<details>
<summary><strong>Configure chat and embedding models</strong></summary>

Configure any compatible chat endpoint in `examples/.env`:

```dotenv
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_OPENAI_CHAT_API_KEY=your-chat-key
SPRING_AI_OPENAI_CHAT_BASE_URL=https://api.deepseek.com
SPRING_AI_OPENAI_CHAT_MODEL=deepseek-v4-flash
SPRING_AI_OPENAI_CHAT_TIMEOUT=120s
```

Knowledge ingestion and semantic retrieval additionally require an embedding endpoint:

```dotenv
SPRING_AI_MODEL_EMBEDDING=openai
SPRING_AI_OPENAI_EMBEDDING_API_KEY=your-embedding-key
SPRING_AI_OPENAI_EMBEDDING_BASE_URL=https://api.openai.com
SPRING_AI_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
SPRING_AI_OPENAI_EMBEDDING_DIMENSION=1536
```

Chat and embedding endpoints are independent because many OpenAI-compatible chat providers do not expose embeddings. Reindex enabled documents after changing the embedding model or dimension.

</details>

### First-run tour

1. **Data:** ask a business question, inspect the SQL candidate, confirm the read-only query, and create a report handoff.
2. **Knowledge:** initialize fictional data or upload a document, ask a cited question, then complete a structured quality review.
3. **Support:** analyze a fictional ticket, edit and confirm its draft in the human-review queue, then record the business-channel outcome.
4. **Report:** select a prepared Data handoff or enter/upload a source, generate a draft, review evidence, and confirm the report.
5. **HR:** draft and confirm job criteria, review a fictional resume, and inspect the grouped recruiting and employee-service navigation.

## Product tour

| Data result handoff | Knowledge quality review |
|---|---|
| ![Data result handoff](assets/data-copilot-result.png) | ![Knowledge quality review](assets/knowledge-copilot-result.png) |

| Support human-review queue | Report from a Data handoff |
|---|---|
| ![Support human-review queue](assets/support-copilot-result.png) | ![Report generation from a Data handoff](assets/report-copilot-result.png) |

![Grouped recruiting and employee-service navigation](assets/resume-copilot-result.png)

All visuals use fictional data captured from the runnable Docker Compose application.

## Trust built into the workflow

- Typed model outputs pass deterministic, module-specific guardrails before affecting business state.
- Knowledge citations, report source IDs, support evidence versions, and HR evidence remain inspectable.
- Actor-bound, single-use confirmation protects high-risk state changes and detects expiry, replay, and conflicts.
- Data Copilot combines application guardrails with an independently restricted database reader.
- Request IDs, model and policy metadata, latency, lifecycle state, and bounded audit retention keep failures diagnosable.
- External connections fail closed through HTTPS allowlists, DNS/IP checks, redirect blocking, bounded responses, and environment-only secret references.

## Architecture

The repository is a modular monolith: one deployable Spring Boot application, five independently auto-configured business modules, and a platform layer extracted only from proven shared use.

```mermaid
flowchart LR
    UI["Vue 3 + TypeScript workbench"] --> APP["business-copilot-app"]
    APP --> DATA["Data"] & KNOW["Knowledge"] & SUPPORT["Support"] & REPORT["Report"] & HR["HR"]
    KNOW & REPORT & HR --> DOC["document-processing"]
    DATA & KNOW & SUPPORT & REPORT & HR --> AI["ai-core"]
    DATA & KNOW & SUPPORT & REPORT & HR --> GUARD["ai-guardrails"]
    DATA & KNOW & SUPPORT & REPORT & HR --> WEB["common-web"]
    APP --> DB[("PostgreSQL + pgvector")]
    DATA -. optional read-only target .-> BIZ[("PostgreSQL or MySQL")]
```

| Layer | Technology | Responsibility |
|---|---|---|
| Runtime | Java 21, Spring Boot 4.1 | One executable application with explicit module auto-configuration |
| AI | Spring AI 2.0, Jackson 3 | Central prompts, typed output, timeouts, retry, concurrency isolation, and circuit breakers |
| Persistence | Spring JDBC, Flyway | Explicit repositories, conditional state transitions, migrations, and pgvector access |
| Web | Vue 3, TypeScript, Vite, Spring MVC | Bilingual same-origin SPA packaged into the executable JAR |
| Delivery | Docker Compose, GitHub Actions, CycloneDX | Reproducible startup, evaluation gates, integration tests, SBOM generation, recurring Trivy scans, and dependency maintenance |

## Deployment and integration status

| Capability | Status | Deployment responsibility |
|---|---|---|
| Local Docker Compose | Runnable sample | Replace demo passwords before any shared deployment |
| Self-hosted application | Supported reference deployment | Configure identity, network, secrets, retention, privacy, and provider terms |
| External PostgreSQL/MySQL query target | Implemented and integration-tested | Provision an independent least-privilege `SELECT` account and explicit allowlists |
| SharePoint, Confluence, Notion, Jira, support, meeting, and ATS adapters | Configurable integration points | Provide credentials, allowed hosts, object permissions, and vendor-sandbox validation |
| Public demo profile | Controlled fictional-data evaluation | Keep uploads and external actions disabled; configure quotas and model budgets |

The presence of an adapter is not a claim of vendor certification. Review [SECURITY.md](SECURITY.md) before any production-like deployment.

## Develop and contribute

Local source development uses Java 21, Node 22, and PostgreSQL 16 with pgvector. Maven installs pinned frontend tooling for reproducible builds.

```bash
./scripts/check-frontend-syntax.sh
./scripts/check-evaluation-datasets.sh
./mvnw --batch-mode --no-transfer-progress verify -Psbom
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and focused frontend/E2E commands. Use only fictional, sanitized data in issues, tests, screenshots, and pull requests.

## Project resources

| Resource | Link |
|---|---|
| Stable release | [v2.3.1](https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v2.3.1) |
| Release history | [CHANGELOG.md](CHANGELOG.md) · [GitHub Releases](https://github.com/qcodingdev/spring-ai-business-copilot/releases) |
| Upgrade and verification | [2.3.0 → 2.3.1](docs/upgrade-2.3.0-to-2.3.1.md) · [2.3.1 release validation](docs/release-validation-2.3.1.md) |
| Questions and bugs | [GitHub Issues](https://github.com/qcodingdev/spring-ai-business-copilot/issues) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Security reports | [SECURITY.md](SECURITY.md) |

Spring AI Business Copilot is released under the [MIT License](LICENSE).
