# Spring AI Business Copilot

English | [简体中文](README.zh-CN.md)

Spring AI Business Copilot is an open-source Java AI business application suite for individuals, small teams, and internal enterprise systems.

It provides ready-to-run business modules that teams can clone, run, learn from, and adapt to real business systems. The goal is not to provide another AI framework, but to provide practical Spring AI applications.

> **V1 status:** Only Data Copilot is implemented. Other modules (Resume Copilot, Support Copilot, Knowledge Copilot, Report Copilot) are reserved for future work and are not yet usable.

---

## Quick Start

### Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 16 (or Docker)
- An OpenAI-compatible chat model API key (optional; the app runs with AI features disabled if absent)

### Option 1: Docker Compose (Recommended)

```bash
cd examples
cp .env.example .env
# Edit .env and add your API key if you have one:
#   SPRING_AI_OPENAI_API_KEY=sk-...
docker compose up --build
```

The app starts at **http://localhost:8080**. PostgreSQL is exposed on port 5432.

Flyway automatically creates sample business tables (customers, products, orders, etc.) and the audit log table on first run.

### Option 2: Local Development

1. Start a PostgreSQL instance and create a database:

```sql
CREATE DATABASE business_copilot;
```

2. Run the application:

```bash
./mvnw spring-boot:run -pl app/business-copilot-app \
  -DSPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/business_copilot \
  -DSPRING_DATASOURCE_USERNAME=copilot \
  -DSPRING_DATASOURCE_PASSWORD=copilot \
  -DSPRING_AI_OPENAI_API_KEY=sk-... \
  -DSPRING_AI_OPENAI_BASE_URL=https://api.openai.com \
  -DSPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=gpt-4o-mini
```

3. Open **http://localhost:8080** in your browser.

### Spring AI / OpenAI-Compatible Model Configuration

The app uses Spring AI with an OpenAI-compatible API. Configure via environment variables or `application.yml`:

| Variable | Default | Description |
|---|---|---|
| `SPRING_AI_MODEL_CHAT` | `none` | Set to `openai` to enable AI features |
| `SPRING_AI_OPENAI_API_KEY` | _(empty)_ | Your API key |
| `SPRING_AI_OPENAI_BASE_URL` | `https://api.openai.com` | Base URL (change for compatible providers) |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` | `gpt-4o-mini` | Model name |

When `SPRING_AI_MODEL_CHAT=none` (the default), AI features are disabled. The workbench still loads, but SQL generation will return an error. This is useful for infrastructure testing without an API key.

---

## First Module: Data Copilot

Data Copilot is a natural-language database query assistant. Users ask business questions and get safe, explainable SQL query results.

**Core flow:**

1. User types a business question (e.g. "What was last month's total revenue?")
2. System generates a SQL candidate and runs it through guardrails
3. User reviews the SQL and confirms execution
4. System executes the read-only SQL, masks sensitive fields, and returns results with an AI explanation

**Safety defaults:**

- **Read-only by default** — only `SELECT` and `WITH ... SELECT` are allowed; `INSERT`, `UPDATE`, `DELETE`, `DROP`, etc. are blocked
- **Confirmation required** — SQL is shown to the user before execution; only server-stored SQL is executed (client-side SQL is never trusted)
- **Guardrails enforced twice** — at generation time and again at execution time
- **Sensitive field masking** — phone and email are partially masked in results; password, token, secret, and id_card are blocked from queries entirely
- **Audit logging** — every query lifecycle event (success, failure, validation failure, user cancellation) is recorded
- **Result truncation** — queries are capped at 100 rows by default

See [docs/data-copilot.md](docs/data-copilot.md) for the full module documentation.

---

## Project Structure

```
spring-ai-business-copilot/
├── app/business-copilot-app/       # Spring Boot application entry point
├── platform/
│   ├── ai-core/                    # LLM integration, prompt templates
│   ├── ai-guardrails/              # SQL safety, sensitive-field policies
│   ├── ai-tool-audit/              # Query audit logging
│   └── common-web/                 # Unified API response, error handling
├── modules/
│   └── data-copilot/               # Data Copilot module (v1)
├── examples/
│   ├── docker-compose.yml          # One-command startup
│   └── .env.example                # Environment variable template
├── Dockerfile                       # Multi-stage build
└── docs/
    └── data-copilot.md             # Module documentation
```

---

## Tech Stack

- Java 21
- Spring Boot 4.1
- Spring AI 2.0
- Spring JDBC + PostgreSQL
- Flyway database migrations
- Thymeleaf (workbench UI)
- Maven multi-module

---

## License

This project is licensed under the terms found in the [LICENSE](LICENSE) file.
