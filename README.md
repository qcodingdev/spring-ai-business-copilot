<h1 align="center">Spring AI Business Copilot</h1>

<p align="center">
  <strong>Build real AI business applications with Java and Spring AI.</strong><br>
  RAG knowledge base · Text-to-SQL · AI-assisted support · Report generation · HR Copilot
</p>

<p align="center">
  <a href="https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v2.4.1"><img alt="Release v2.4.1" src="https://img.shields.io/badge/Release-v2.4.1-2563EB"></a>
  <a href="https://openjdk.org/projects/jdk/21/"><img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&amp;logoColor=white"></a>
  <a href="https://spring.io/projects/spring-boot"><img alt="Spring Boot 4.1" src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&amp;logoColor=white"></a>
  <a href="https://spring.io/projects/spring-ai"><img alt="Spring AI 2.0" src="https://img.shields.io/badge/Spring%20AI-2.0-6DB33F"></a>
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/License-MIT-blue"></a>
</p>

<p align="center">
  <a href="README.zh-CN.md"><strong>简体中文说明</strong></a> ·
  <a href="#quick-preview">Demo GIF</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#current-business-capabilities">Capabilities</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v2.4.1">Stable v2.4.1</a> ·
  <a href="https://gitee.com/qcodingdev/spring-ai-business-copilot">Gitee</a>
</p>

## Who is this for?

If you are learning or building enterprise AI applications with Java / Spring AI, this project provides a runnable full-stack reference: RAG knowledge Q&A, natural-language SQL queries, support reply drafts, business reports, and HR assistance. Follow each workflow from the web page and API through model calls to saved results.

- **Learning Spring AI:** explore document retrieval, cited answers, and SQL generation in a working application.
- **Building a business project:** start with one of the five modules and adapt its UI, APIs, and persisted workflow to your needs.
- **Evaluating delivery:** inspect safety checks (Guardrails), human confirmation (Human-in-the-loop), audit records, and operational checks as a starting point for enterprise AI governance.

## Quick preview

![Spring AI Business Copilot 2.3 workbench](assets/workbench-demo.gif)

A roughly 12-second page tour of the five-module workbench, captured in v2.3 with fictional data.

**[Run it locally](#quick-start)** · [Explore the five workflows](#current-business-capabilities)

You can explore the interface without a model key. AI generation requires a chat model; Knowledge ingestion and Q&A also require an embedding model. See the configuration modes below.

## Quick start

Requirements: Docker with Compose support.

```bash
git clone --branch v2.4.1 --single-branch \
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

Administrators can then open **System administration → Enterprise readiness**, follow any remediation link back to these five workflows, rerun the checks, and save a purpose-bound evidence snapshot.

<details>
<summary>Use existing enterprise identities (optional OIDC)</summary>

For a single-organization pilot, set `SPRING_PROFILES_ACTIVE=self-hosted,oidc` (or `prod,oidc`) and the `BUSINESS_COPILOT_OIDC_ISSUER_URI`, `BUSINESS_COPILOT_OIDC_CLIENT_ID`, and `BUSINESS_COPILOT_OIDC_CLIENT_SECRET` variables in your secret-backed deployment. Register `https://<your-host>/login/oauth2/code/enterprise` as the redirect URI. The signed ID token must contain the application-specific array `business_copilot_roles` with `ADMIN`, `OPERATOR`, or `REVIEWER`; the claim name is configurable. Keep operator and reviewer assignments separate for independent review.

The login page exposes enterprise sign-in and disables local sample accounts. Ownership and audit use a stable actor derived from issuer and subject, so a display-name or email change does not transfer ownership. Existing local-account records are not automatically reassigned. The session expires at ID-token expiry or after 20 idle minutes; immediate IdP revocation and single logout still require deployment acceptance. Configure short token lifetimes at the IdP. Use HTTPS; enable forwarded headers only behind an ingress that strips untrusted forwarding headers. See the [environment example](examples/.env.example). This mode must be accepted against your actual IdP and is separate from `public-demo`.

</details>


## Current business capabilities

| Domain | Operational flow available today | Key control |
|---|---|---|
| [Data analysis](modules/data-copilot/README.md) | Generate SQL candidates from natural language; govern metric definitions and approved templates; inspect result snapshots and audits; hand masked results to Report | Queries are read-only and bounded by schema, columns, functions, rows, time, and result size, with confirmation before execution |
| [Enterprise knowledge](modules/knowledge-copilot/README.md) | Upload and manage documents; synchronize governed sources; ask cited questions; process a quality queue with separate evidence, answer, remediation, and disposition fields | The system refuses to answer without current accessible evidence, and every citation resolves to the current document version |
| [Customer operations](modules/support-copilot/README.md) | Analyze tickets with SLA and similar-case context; revise and confirm drafts in a human-review queue; manage external connections and outcome records | Confirming a draft does not send a customer message; external internal-note writeback requires a separate preview and confirmation |
| [Business reporting](modules/report-copilot/README.md) | Start from a Data handoff that fills title and source automatically, or use typed/CSV/JSON input; generate, edit, confirm, schedule, and export reports | Facts remain bound to immutable source snapshots; schedules create reviewable drafts and never auto-publish |
| [Recruiting and employee services](modules/resume-copilot/README.md) | Recruiting covers job criteria, evidence-based resume review, interviews, candidate consent, and read-only ATS import; employee services cover cited policy Q&A and onboarding checklists | No score, ranking, hire/reject conclusion, or ATS write action is produced |

## Product tour

| Data result handoff | Knowledge quality review |
|---|---|
| ![Data result handoff](assets/data-copilot-result.png) | ![Knowledge quality review](assets/knowledge-copilot-result.png) |

| Support human-review queue | Report from a Data handoff |
|---|---|
| ![Support human-review queue](assets/support-copilot-result.png) | ![Report generation from a Data handoff](assets/report-copilot-result.png) |

![Grouped recruiting and employee-service navigation](assets/resume-copilot-result.png)

All visuals use fictional data captured from the runnable Docker Compose application.

## One workbench for five enterprise domains

Spring AI Business Copilot has grown from the original Data Copilot into a unified workbench for data analysis, enterprise knowledge, customer operations, business reporting, recruiting, and employee services. Each domain can run independently, while data handoffs, grounded knowledge, human review, and persisted state connect them into operational workflows.

The `2.3` line productized these existing capabilities rather than adding more modules. `2.3.1` strengthened the external-integration and maintenance baseline; `2.4.0` adds enterprise-readiness evidence without introducing a sixth business domain:

- **Unified enterprise workbench:** a bilingual Vue 3 + TypeScript interface brings together the overview, five business domains, and system administration, with actions scoped to `ADMIN`, `OPERATOR`, and `REVIEWER` roles.
- **Cross-domain collaboration:** Data results flow into Report; Knowledge grounds support and employee-policy answers; external tickets, knowledge sources, report inputs, and ATS records enter controlled module workflows.
- **Complete human review:** SQL execution, knowledge-quality disposition, support drafts, report confirmation, and recruiting assessments retain evidence, risk, state, human edits, and confirmation records.
- **Diagnosable delivery:** administration covers runtime health, AI call chains, token/latency visibility, knowledge documents, and experience data; Docker Compose, automated tests, SBOM, and security gates cover delivery.
- **Maintained integrations:** Notion uses the current `2026-03-11` API contract and bounded full-page traversal; direct contracts cover SharePoint, Confluence, Notion, Jira Service Management, Zendesk, ServiceNow, Feishu, and WeCom.
- **Readiness evidence:** seven model/module prerequisites and thirteen operational checks cover configuration, stale claims, uncertain writebacks, invalid knowledge, unrecovered failures, SLA breaches, and due reviews; Admin can remediate, rerun, and retain append-only application evidence with bounded validity and retention.

## Trust built into the workflow

- Typed model outputs pass deterministic, module-specific guardrails before affecting business state.
- Knowledge citations, report source IDs, support evidence versions, and HR evidence remain inspectable.
- Actor-bound, single-use confirmation protects high-risk state changes and detects expiry, replay, and conflicts.
- Data Copilot combines application guardrails with an independently restricted database reader.
- Request IDs, model and policy metadata, latency, lifecycle state, and bounded audit retention keep failures diagnosable.
- External connections fail closed through HTTPS allowlists, DNS/IP checks, redirect blocking, bounded responses, and environment-only secret references.
- Knowledge retrieval excludes expired or conflicted documents across text, keyword, and vector paths; source ACL changes update retrieval visibility even when content is unchanged; missing model/module prerequisites return `NOT_CONFIGURED` instead of a false `READY` state.

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

> **Stable release:** `v2.4.1` adds governed task recovery, versioned evaluation and Prompt review, and an optional enterprise OIDC profile. Data-to-Report metrics retain their source and period definitions, while the five business workflows keep human review and audit boundaries. Production deployment still requires deployment-owned identity, secrets, network policy, retention settings, real-model evaluation, and vendor sandbox acceptance.

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
| Stable release | [v2.4.1](https://github.com/qcodingdev/spring-ai-business-copilot/releases/tag/v2.4.1) |
| Release history | [CHANGELOG.md](CHANGELOG.md) · [GitHub Releases](https://github.com/qcodingdev/spring-ai-business-copilot/releases) |
| Questions and bugs | [GitHub Issues](https://github.com/qcodingdev/spring-ai-business-copilot/issues) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Security reports | [SECURITY.md](SECURITY.md) |

Spring AI Business Copilot is released under the [MIT License](LICENSE).
