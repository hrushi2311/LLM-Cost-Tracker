## LLM Cost Tracker – Build Spec

Nov 17, 2026 · @Hrushikesh

### How to use this spec with Claude Code

Export this doc as Markdown, save it as SPEC.md in an empty repo, and start Claude Code with: "Read SPEC.md. Build milestone 1 only, then stop and show me the acceptance checks passing." Repeat for each milestone.

Working rules for Claude Code:

- Build one milestone at a time (see Milestones). Do not start the next until its acceptance criteria pass and all tests are green.

- Ask before adding any dependency not listed under Tech stack.

- Never store, log or print prompt or response content (see Security and privacy).

- The proxy is transparent: the bytes a client receives must be exactly what the provider sent.

- Write tests with the code. Tests use a mock provider, never real API keys.

- When this spec is ambiguous, pick the simplest option, record it in docs/DECISIONS.md, and continue.

- Use Conventional Commits and keep README.md updated as features land.

- Write everything from scratch. Do not copy code from any employer or client project.

### Product overview

LLM Cost Tracker is a self-hosted proxy and dashboard that records the tokens and cost of every LLM call, broken down by project, model, agent and run. It ships as one Spring Boot container plus Postgres, and an app connects by changing only its LLM base URL. "LLM Cost Tracker" is a working name.

Pitch: a lightweight, self-hosted LLM cost tracker that discovers your agents automatically. One container plus Postgres, any language, no SDK.

Who it is for: small teams and solo developers who want cost visibility without running a full observability stack; JVM, Spring and Akka teams poorly served by Python-first SDKs; anyone building multi-agent apps who needs cost per agent.

| Existing tool | What it does well | Gap this project fills |
|---|---|---|
| Helicone | Proxy with sessions and custom-property headers; self-hostable | Agents still need headers on every call; several services to run |
| Langfuse | Deep tracing, evals, prompt management; MIT, self-hostable | Needs Postgres, ClickHouse, Redis and S3; SDK or OTel instrumentation |
| AgentOps | Agent-focused tracing and per-agent cost | Python SDK only; code changes required |
| LiteLLM proxy | Multi-provider gateway with spend tracking | Gateway first; no automatic agent discovery |

The differentiators to protect in every design decision:

- One app plus Postgres, started with docker compose up.

- No SDK and no code changes: works with any language through the base URL.

- Agents discovered automatically by URL path, header or system-prompt fingerprint, then named in the UI.

- Budgets per agent as well as per project.

- Privacy by default: prompts and responses are never stored.

- Cost only: no evals, prompt management or replay.

### Scope

Version 0.1 covers two providers, three endpoints, agent and run attribution, budgets, and a four-screen dashboard. Everything else waits.

In scope for v0.1

- Providers: OpenAI and Anthropic, plus any OpenAI-compatible endpoint configured as a custom provider.

- Endpoints: OpenAI Chat Completions (/v1/chat/completions), OpenAI Responses (/v1/responses), Anthropic Messages (/v1/messages). Other paths are forwarded unchanged but not recorded.

- Streaming and non-streaming requests.

- Projects with project keys.

- Agent attribution by URL path, header and system-prompt fingerprint; rename and merge agents.

- Runs via an optional run ID header; free-form tags via headers.

- Cost from a versioned price table, editable in the UI.

- Budgets per project and per agent, daily or monthly, with alerts; optional enforcement.

- Dashboard: Overview, Agents, Runs, Connect app, Settings (prices, budgets). CSV export.

- Single admin login.

Out of scope (do not build)

- Storing prompts or responses, replay, evals, prompt management.

- Caching, retries, fallbacks or routing between providers.

- Hosted multi-tenant SaaS, user accounts, SSO, roles.

- Providers beyond the two above (Gemini, Bedrock and others come after v0.1).

- Embeddings, images, audio and batch APIs (forwarded, not recorded).

### Architecture

One Spring Boot app sits between the user's app and the providers; it returns every response untouched and records usage asynchronously into Postgres.

architecture · one app, one database

The proxy is the only path for LLM traffic; everything below it runs after the response and can fail without affecting the client.

Request flow:

- The app calls /proxy/{provider}[/agent/{name}]/… with its own provider key and X-Tracker-Key.

- The proxy validates the project key, resolves the agent and run, strips X-Tracker-* headers, and forwards the request.

- The response streams back to the app byte for byte while a copy is parsed for usage.

- A UsageEvent goes onto the in-memory queue; the batch writer prices it, inserts it, updates agent last_seen and budget spend.

- Budget checks fire alerts; the dashboard reads aggregates from Postgres through /api.

### Tech stack and repository structure

Backend is Java 21 with Spring Boot 3 (latest stable) on WebFlux; the Vue dashboard is built into the same jar so the release is one image.

| Area | Choice | Notes |
|---|---|---|
| Language | Java 21 | Records, virtual threads allowed for the batch writer |
| Web | Spring Boot 3, WebFlux, WebClient | Non-blocking proxy; SSE pass-through |
| Database | PostgreSQL 16 | NUMERIC for money, JSONB for tags |
| Data access | JdbcTemplate + Flyway | Blocking JDBC only on Schedulers.boundedElastic() or the writer thread |
| Build | Gradle (Kotlin DSL), Gradle wrapper | Builds the frontend too, via a Node plugin or an exec task |
| Frontend | Vue 3, Vite, TypeScript, Vue Router, Chart.js | Served as static files from the jar |
| Tests | JUnit 5, AssertJ, Testcontainers, OkHttp MockWebServer | MockWebServer plays OpenAI and Anthropic |
| Packaging | Multi-stage Dockerfile, docker-compose.yml | Non-root user, JRE-only runtime image |
| CI | GitHub Actions | Test on PR; publish image to GHCR on tag |
| Metrics | Spring Boot Actuator, Micrometer | /actuator/health; Prometheus optional |

```
llm-cost-tracker/
├── SPEC.md
├── README.md  LICENSE  CONTRIBUTING.md  SECURITY.md
├── docker-compose.yml  Dockerfile  .env.example
├── docs/DECISIONS.md
├── backend/
│   ├── build.gradle.kts
│   └── src/main/java/dev/llmcosttracker/
│       ├── proxy/        # routing, forwarding, SSE tee
│       ├── provider/     # OpenAI + Anthropic usage parsers
│       ├── attribution/  # agent, run, tags, fingerprint
│       ├── pricing/      # price table, cost calculator
│       ├── ingest/       # event queue + batch writer
│       ├── budget/       # budgets, alerts, webhook
│       ├── api/          # dashboard REST controllers
│       ├── auth/         # admin login, project keys
│       └── config/
│   └── src/main/resources/
│       ├── application.yml
│       ├── db/migration/V1__init.sql
│       └── prices/seed-prices.yaml
├── frontend/
│   └── src/ (views/, components/, api/, router/)
└── .github/workflows/ (ci.yml, release.yml)
```

The Java package name dev.llmcosttracker is a placeholder; change it when the project is named.

### Proxy behaviour

The proxy forwards every request unchanged to the provider, returns the provider's response unchanged, and records usage on the side without ever delaying the client.

#### Routes

{METHOD} /proxy/{provider}[/agent/{agentName}]/{path...}

| Provider key | Upstream base (default, configurable) | Client sets | Example upstream call |
|---|---|---|---|
| openai | https://api.openai.com/v1 | OPENAI_BASE_URL=http://host:8080/proxy/openai | /proxy/openai/chat/completions → https://api.openai.com/v1/chat/completions |
| anthropic | https://api.anthropic.com | ANTHROPIC_BASE_URL=http://host:8080/proxy/anthropic | /proxy/anthropic/v1/messages → https://api.anthropic.com/v1/messages |
| custom (from config) | any OpenAI-compatible base URL | same pattern | parsed with the OpenAI parsers |

Unknown provider key → 404 with a JSON error. The agent/{agentName} segment is stripped before forwarding.

#### Forwarding rules

- All methods and paths are forwarded; only the three endpoints in Scope are recorded.

- Copy request headers except hop-by-hop headers, Host, and every X-Tracker-* header.

- Authorization and x-api-key pass through untouched and are never stored or logged.

- Return status, headers and body exactly as received. Do not re-serialise JSON.

- Timeouts: connect 10 s, response 10 min (configurable); request body limit 20 MB (configurable).

- Validate the project key (X-Tracker-Key) before forwarding. Missing or unknown key → 401 JSON error when TRACKER_REQUIRE_PROJECT_KEY=true (default).

#### Usage extraction

Read usage from the response body (non-streaming) or from the stream events (streaming). The model is the response's model field, falling back to the request's.

| Endpoint | Input | Output | Cache read | Cache write | Reasoning (info only) |
|---|---|---|---|---|---|
| OpenAI Chat Completions | usage.prompt_tokens (includes cached) | usage.completion_tokens | usage.prompt_tokens_details.cached_tokens | none | usage.completion_tokens_details.reasoning_tokens |
| OpenAI Responses | usage.input_tokens (includes cached) | usage.output_tokens | usage.input_tokens_details.cached_tokens | none | usage.output_tokens_details.reasoning_tokens |
| Anthropic Messages | usage.input_tokens (excludes cache) | usage.output_tokens | usage.cache_read_input_tokens | usage.cache_creation_input_tokens | none |

Normalise before storing: input_tokens = uncached input billed at the full rate. For OpenAI, subtract cached tokens from the reported input; for Anthropic, store as reported. Reasoning tokens are already inside OpenAI output tokens; store them separately for display only and never add them to cost twice. Verify all field names against current provider docs while building, and keep one fixture JSON per endpoint in the tests.

#### Streaming

- Pass SSE bytes to the client as they arrive. Parse a copy (a tee) to find usage; never buffer the whole stream before sending.

- OpenAI Chat Completions: usage arrives only when stream_options.include_usage is true. If the client did not set it, add it (TRACKER_OPENAI_INJECT_STREAM_USAGE=true by default) and document that clients then receive one extra final chunk with empty choices.

- OpenAI Responses: read usage from the response.completed event.

- Anthropic: input and cache tokens from message_start; final output tokens from the last message_delta.

- Client disconnects mid-stream: record what was seen, mark the event incomplete.

#### Recording pipeline and failures

- After the response completes, build a UsageEvent and offer it to a bounded in-memory queue (default capacity 10,000).

- A single writer drains the queue and batch-inserts every 1 second or 500 events, whichever comes first.

- Queue full or database down: drop the event, increment a tracker.events.dropped counter, log a warning at most once a minute. The client call must still succeed.

- Upstream error (4xx/5xx): record the event with its status code and any usage present (usually zero).

- Latency is measured from receiving the request to the last byte sent to the client.

### Agent and run attribution

Every recorded call is tied to exactly one agent, resolved in a fixed order, and agents are created on first sight with no registration step.

#### Resolving the agent (first match wins)

- X-Tracker-Agent header.

- The agent/{agentName} URL segment.

- System-prompt fingerprint, when TRACKER_FINGERPRINT_ENABLED=true (default).

- Otherwise the agent named unattributed.

Before lookup, check agent_alias for the resolved name or fp:<hash>; an alias points to the agent it was merged into.

#### Names

- Normalise to a slug: lowercase, [a-z0-9-], other characters become -, collapse repeats, trim dashes, max 64 characters. Empty after normalising → treat as absent.

- Upsert on (project_id, name): create on first sight, update last_seen on each event (in the batch writer, not per request).

#### Fingerprinting

- Extract the system prompt: OpenAI Chat Completions → all system and developer messages joined with newlines; OpenAI Responses → instructions; Anthropic → top-level system (string, or the text of its blocks).

- Normalise: trim, collapse whitespace. Optional masking (TRACKER_FINGERPRINT_MASK=true) replaces numbers, UUIDs, ISO dates and emails with placeholders so templated prompts do not split into many agents.

- Hash with SHA-256; store only the hex hash. Agent name = agent- + first 4 hex characters (show it as agent·a3f9 in the UI). On a 4-character collision within a project, extend to 6, then 8.

- No system prompt found → unattributed.

#### Rename and merge

- Rename sets display_name; the slug never changes, so URL-path and header matching keep working.

- Merge A into B: add aliases for A's name and fingerprint pointing to B, reassign A's events to B in one transaction, delete A.

#### Runs and tags

- X-Tracker-Run-Id: optional, max 128 printable characters, stored as run_id. Runs are derived from events; there is no runs table.

- X-Tracker-Tag-<key>: <value>: up to 10 tags per request, key slugged (max 32), value max 128, stored in tags JSONB. Extra or invalid tags are ignored with a debug log.

- All X-Tracker-* headers are removed before forwarding.

### Data model

Seven tables in one Flyway migration, V1__init.sql; every later change is a new migration, never an edit to an applied one.

```
CREATE TABLE project (
  id          UUID PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  key_hash    TEXT NOT NULL UNIQUE,      -- SHA-256 of the full project key
  key_last4   TEXT NOT NULL,             -- shown masked in the UI
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent (
  id            UUID PRIMARY KEY,
  project_id    UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,           -- slug, never changes
  display_name  TEXT,
  source        TEXT NOT NULL CHECK (source IN ('path','header','fingerprint','unattributed')),
  fingerprint   TEXT,                    -- SHA-256 hex, fingerprint agents only
  first_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, name)
);

CREATE TABLE agent_alias (
  project_id  UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  alias       TEXT NOT NULL,             -- a slug or 'fp:<hash>'
  agent_id    UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
  PRIMARY KEY (project_id, alias)
);

CREATE TABLE model_price (
  provider               TEXT NOT NULL,
  model                  TEXT NOT NULL,  -- exact name or prefix (see Cost calculation)
  effective_from         DATE NOT NULL,
  input_per_mtok         NUMERIC(12,6) NOT NULL,  -- USD per 1M tokens
  output_per_mtok        NUMERIC(12,6) NOT NULL,
  cache_read_per_mtok    NUMERIC(12,6),
  cache_write_per_mtok   NUMERIC(12,6),
  source                 TEXT NOT NULL DEFAULT 'seed' CHECK (source IN ('seed','user')),
  PRIMARY KEY (provider, model, effective_from)
);

CREATE TABLE usage_event (
  id                  BIGSERIAL PRIMARY KEY,
  project_id          UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  agent_id            UUID REFERENCES agent(id) ON DELETE SET NULL,
  run_id              TEXT,
  provider            TEXT NOT NULL,
  endpoint            TEXT NOT NULL,     -- 'chat.completions' | 'responses' | 'messages'
  model               TEXT,
  input_tokens        INTEGER NOT NULL DEFAULT 0,  -- uncached input
  output_tokens       INTEGER NOT NULL DEFAULT 0,
  cache_read_tokens   INTEGER NOT NULL DEFAULT 0,
  cache_write_tokens  INTEGER NOT NULL DEFAULT 0,
  reasoning_tokens    INTEGER NOT NULL DEFAULT 0,  -- display only
  cost_usd            NUMERIC(14,8),     -- NULL when unpriced
  streamed            BOOLEAN NOT NULL DEFAULT false,
  incomplete          BOOLEAN NOT NULL DEFAULT false,
  status_code         INTEGER NOT NULL,
  latency_ms          INTEGER NOT NULL,
  tags                JSONB,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ue_project_time ON usage_event (project_id, created_at);
CREATE INDEX ue_agent_time   ON usage_event (project_id, agent_id, created_at);
CREATE INDEX ue_run          ON usage_event (project_id, run_id, created_at) WHERE run_id IS NOT NULL;
CREATE INDEX ue_unpriced     ON usage_event (provider, model) WHERE cost_usd IS NULL;

CREATE TABLE budget (
  id          UUID PRIMARY KEY,
  project_id  UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  agent_id    UUID REFERENCES agent(id) ON DELETE CASCADE,  -- NULL = whole project
  period      TEXT NOT NULL CHECK (period IN ('day','month')),
  limit_usd   NUMERIC(12,2) NOT NULL CHECK (limit_usd > 0),
  alert_pct   INTEGER NOT NULL DEFAULT 80 CHECK (alert_pct BETWEEN 1 AND 100),
  enforce     BOOLEAN NOT NULL DEFAULT false,
  UNIQUE NULLS NOT DISTINCT (project_id, agent_id, period)
);

CREATE TABLE alert_event (
  id            BIGSERIAL PRIMARY KEY,
  budget_id     UUID NOT NULL REFERENCES budget(id) ON DELETE CASCADE,
  period_start  DATE NOT NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('threshold','exceeded')),
  spent_usd     NUMERIC(12,2) NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (budget_id, period_start, kind)
);
```

Notes:

- Money is NUMERIC in the database and BigDecimal in Java. Never double. The API sends money as decimal strings.

- All timestamps are UTC; budget periods use TRACKER_TIMEZONE.

- If dashboard queries get slow past a few million events, add a daily rollup table in a later migration; do not build it in v0.1.

### Cost calculation and price table

Cost is computed once, when the event is written, from the price row in effect on the event's date; an event with no matching price is stored with a null cost and priced later.

\text{cost} = \frac{T_{in}\,P_{in} + T_{out}\,P_{out} + T_{cr}\,P_{cr} + T_{cw}\,P_{cw}}{10^{6}}

Here T are the normalised token counts (uncached input, output, cache read, cache write) and P the per-million-token USD prices. A missing cache price falls back to the input price.

#### Price lookup

- Same provider, effective_from on or before the event date, latest such row.

- Exact model match first; otherwise the longest stored model name that is a prefix of the event's model (so a dated snapshot such as gpt-4o-2024-08-06 matches gpt-4o).

- No match → cost_usd = NULL; the UI shows the model as "unpriced" with a link to add a price.

#### Price data

- Ship prices/seed-prices.yaml with provider, model, effective date and the four prices, plus a checked_on date and source URL per row. Load it at startup with insert-if-absent, so user edits are never overwritten.

- Fill the seed file from the providers' official pricing pages at build time and record the date checked. Do not take prices from memory or from this spec.

- Price changes are new rows with a new effective_from, so past costs never change.

- POST /api/prices/recompute re-prices events where cost_usd IS NULL (and, with ?all=true, every event in a date range). Run it in batches of 5,000.

- Batch, cached-context, long-context and regional price tiers are out of scope for v0.1; note the limitation in the README.

### Budgets and alerts

A budget caps spend for a project or a single agent per day or month; it alerts once at its threshold and once when exceeded, and blocks calls only when enforcement is switched on.

- Periods start at midnight, or on the 1st for monthly, in TRACKER_TIMEZONE (default UTC).

- Spend per budget is kept in memory, increased by the batch writer after each insert and reloaded from the database every 60 seconds and at startup.

- After each batch, check affected budgets. Crossing alert_pct inserts a threshold alert; crossing 100% inserts an exceeded alert. The unique constraint makes each fire once per period.

- Alert delivery: an application log line, a banner on the dashboard, and, if TRACKER_ALERTS_WEBHOOK_URL is set, a JSON POST with a text field (Slack-compatible) plus project, agent, period, limit_usd, spent_usd, kind. Webhook failures are retried 3 times with backoff, then logged.

- Enforcement (off by default, per budget): while an enforced budget is exceeded, the proxy answers 429 before forwarding, with body {"error":{"type":"budget_exceeded","message":"…","budget_id":"…"}}. Because spend is checked after the fact, a few in-flight calls may push slightly past the limit; say so in the docs.

- The Overview projection = spend so far ÷ days elapsed × days in the period. Show the day the alert threshold is likely to be crossed.

### Dashboard REST API

The dashboard talks only to /api/**, which requires the admin session; money is returned as decimal strings and time ranges as ISO-8601 from and to (default: the last 14 days).

| Method | Path | Returns or does |
|---|---|---|
| POST | /api/auth/login | Checks TRACKER_ADMIN_PASSWORD, sets an HttpOnly session cookie |
| POST | /api/auth/logout | Ends the session |
| GET | /api/projects | Projects with masked keys |
| POST | /api/projects | Creates a project; returns the full key once |
| POST | /api/projects/{id}/rotate-key | New key, returned once; the old one stops working |
| GET | /api/projects/{id}/summary | Total cost, tokens, requests, runs, error rate, change vs previous period, budget projection |
| GET | /api/projects/{id}/timeseries?groupBy=agent\|model&interval=hour\|day | Cost per interval per group |
| GET | /api/projects/{id}/models | Per model: provider, requests, tokens, cost, share; unpriced flag |
| GET | /api/projects/{id}/agents | Per agent: source, models, requests, tokens, cost, budget use, first and last seen |
| PATCH | /api/agents/{id} | Sets displayName |
| POST | /api/agents/{id}/merge | Body {"intoAgentId":"…"} |
| GET | /api/projects/{id}/runs?cursor= | Runs newest first: id, calls, agents, cost, duration; cursor paging, 50 per page |
| GET | /api/projects/{id}/runs/{runId} | The run's events in order with offsets for the waterfall |
| GET, POST, PUT, DELETE | /api/projects/{id}/budgets[/{budgetId}] | Budget CRUD |
| GET | /api/projects/{id}/alerts | Recent alert events |
| GET, POST | /api/prices | List prices; add a price row (source='user') |
| POST | /api/prices/recompute | Re-prices unpriced events |
| GET | /api/projects/{id}/events.csv | Streams events as CSV for the range |
| GET | /api/status | Version, last event time, events dropped, queue depth |
| GET | /actuator/health | Public liveness and readiness |

Errors use one shape: {"error":{"code":"…","message":"…"}} with a fitting HTTP status. Validate every input; reject unknown fields.

### Dashboard UI

The dashboard follows the UI design canvas: a left sidebar (project switcher, navigation, proxy status) and five screens. That link is private, so the screens are also described here.

| Screen | Shows | Actions |
|---|---|---|
| Login | Password field | Sign in |
| Overview | KPI tiles (cost with change vs previous period, tokens in/out, requests and runs, average cost per run); period budget bar with projection and alert marker; daily cost chart stacked by agent; cost by model; agents with budget use; banner when a new fingerprint agent appears | Date range 7/14/30 days, export CSV |
| Agents | Table: agent, discovered via (path, header, fingerprint badge), models, requests, tokens in/out, cost, budget bar; detail panel for the selected row: cost by model, match rule, first/last seen | Rename, merge, set budget, view runs |
| Runs | Recent runs list; selected run: cost, tokens, calls, duration; cost split by agent; waterfall of calls (agent, model, tokens, cost, latency bar) | Page through runs |
| Connect app | Live status (last event), project key (masked), base URL per provider, agent URL builder that updates an .env snippet as you type, run ID header example | Reveal, copy, rotate key |
| Settings | Price table with unpriced models highlighted; budgets; webhook test | Add price, recompute, add or edit budget, send test alert |

Visual rules:

- Light theme first; Inter for text, JetBrains Mono for model names, IDs and code; tabular figures for numbers.

- One colour per agent, consistent across every chart on every screen; grey for unnamed fingerprint agents.

- Layout works at phone width: sidebar stacks above content, wide tables scroll horizontally inside their card.

- Real buttons and links, labelled inputs, visible focus, text contrast at least 4.5:1.

- Empty states: a new project shows the Connect app steps instead of empty charts.

- Poll /api/status every 10 s for the live indicator; no websockets in v0.1.

### Configuration, Docker and docker-compose

Everything is configured by environment variables, so docker compose up with an .env file is the whole install.

| Variable | Default | Purpose |
|---|---|---|
| SPRING_DATASOURCE_URL | jdbc:postgresql://db:5432/llmtracker | Database |
| SPRING_DATASOURCE_USERNAME / _PASSWORD | tracker / required | Database credentials |
| TRACKER_ADMIN_PASSWORD | required | Dashboard login; refuse to start if empty or shorter than 12 characters |
| TRACKER_REQUIRE_PROJECT_KEY | true | Reject proxy calls without a valid key |
| TRACKER_OPENAI_BASE_URL | https://api.openai.com/v1 | OpenAI upstream |
| TRACKER_ANTHROPIC_BASE_URL | https://api.anthropic.com | Anthropic upstream |
| TRACKER_CUSTOM_PROVIDERS | empty | name=url pairs, comma separated, OpenAI-compatible |
| TRACKER_OPENAI_INJECT_STREAM_USAGE | true | Add stream_options.include_usage |
| TRACKER_FINGERPRINT_ENABLED / _MASK | true / false | Agent fingerprinting |
| TRACKER_QUEUE_CAPACITY | 10000 | Event queue size |
| TRACKER_TIMEZONE | UTC | Budget periods |
| TRACKER_ALERTS_WEBHOOK_URL | empty | Alert webhook |
| TRACKER_UPSTREAM_TIMEOUT | 10m | Response timeout |
| SERVER_PORT | 8080 | HTTP port |

```
# Dockerfile (multi-stage)
FROM node:lts AS web
WORKDIR /web
COPY frontend/ .
RUN npm ci && npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
COPY --from=web /web/dist backend/src/main/resources/static
RUN ./gradlew :backend:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 tracker
COPY --from=build /src/backend/build/libs/*.jar /app/app.jar
USER tracker
EXPOSE 8080
HEALTHCHECK CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

```
# docker-compose.yml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_DB: llmtracker
      POSTGRES_USER: tracker
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tracker"]
      interval: 5s
      retries: 10

  app:
    image: ghcr.io/OWNER/llm-cost-tracker:latest
    build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/llmtracker
      SPRING_DATASOURCE_USERNAME: tracker
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      TRACKER_ADMIN_PASSWORD: ${TRACKER_ADMIN_PASSWORD:?set TRACKER_ADMIN_PASSWORD in .env}
    depends_on:
      db:
        condition: service_healthy

volumes:
  pgdata:
```

Check that wget exists in the runtime image; if not, install it or use a small Java health check. Ship .env.example with every variable and a comment each. Users with their own Postgres run only the app service and point SPRING_DATASOURCE_URL at it.

### Security and privacy

The tracker sees every prompt and every provider key, so it must keep neither: it stores only counts, costs, timings, names and hashes.

- Never persist or log request or response bodies, prompts, completions or system prompts. Only the SHA-256 fingerprint of a system prompt is stored.

- Never persist or log provider API keys (Authorization, x-api-key). Add a logging filter that redacts these headers, and a test proving they never reach logs.

- Project keys: generate 32 random bytes from SecureRandom, encode as trk_ + base62. Store only the SHA-256 hash and last 4 characters. Show the full key once at creation or rotation. Compare hashes in constant time.

- Dashboard: one admin password from the environment, HttpOnly + SameSite=Strict session cookie, CSRF protection on state-changing /api calls, login rate limit (5 attempts per minute per IP).

- The proxy only calls configured upstream base URLs; never build an upstream host from request data (prevents SSRF).

- Bind to all interfaces by default but document running behind TLS (a reverse proxy) for anything beyond localhost. Never expose the proxy publicly without TRACKER_REQUIRE_PROJECT_KEY=true.

- Dependencies: enable Dependabot; CI fails on known critical vulnerabilities.

- SECURITY.md explains how to report vulnerabilities privately.

### Testing

Every test runs offline: a MockWebServer stands in for OpenAI and Anthropic, Testcontainers provides Postgres, and no real API key appears anywhere in the repo.

| Level | What it covers |
|---|---|
| Unit | Usage parsers per endpoint from fixture JSON and SSE files (including cached and reasoning tokens); token normalisation; cost calculator and prefix price lookup; slug rules; fingerprint extraction and masking; budget period maths across timezones |
| Integration | Real OpenAI and Anthropic SDK clients (Java SDKs) pointed at the proxy, which points at MockWebServer; response bytes identical to the mock's for JSON and SSE; event rows written with the right agent, run, tags and cost; X-Tracker-* and API keys never forwarded or stored; upstream 4xx/5xx passed through and recorded; database stopped mid-test → client calls still succeed and events.dropped increases; enforcement returns 429 |
| API | Each /api endpoint against seeded data; auth required; CSRF; validation errors |
| Frontend | Vitest for formatting helpers and the URL builder; one Playwright smoke test that logs in and opens each screen |
| Load (manual) | 200 concurrent non-streaming calls against a mock with 500 ms latency: added proxy latency under 10 ms at p95 on a laptop; no lost events below queue capacity |

CI runs unit, integration and API tests on every pull request; the load check is a documented script, not a CI step.

### Milestones and build order

Nine milestones, each shippable on its own; Claude Code completes one, proves its acceptance criteria, and stops.

- Skeleton. Gradle multi-project, Spring Boot app, Flyway V1__init.sql, Dockerfile, docker-compose.yml, .env.example, health endpoint, CI workflow.

- Done when: docker compose up gives a healthy app with all tables created; CI is green.

- OpenAI non-streaming proxy. Routes, forwarding, project keys (created through a temporary CLI or SQL seed), Chat Completions usage parsing, cost, event queue and batch writer.

- Done when: the official OpenAI Java SDK, with only its base URL changed, works unchanged against the mock; a correct usage_event row is written; a stopped database does not break calls.

- Anthropic and Responses API. Messages and Responses parsers, cache-token normalisation, custom OpenAI-compatible providers.

- Done when: fixture tests pass for all three endpoints and costs match hand-computed values.

- Attribution. Agent from header, path and fingerprint; aliases; run ID; tags.

- Done when: one test client using three agents (one per method) produces three agents with correct sources and a run that groups their calls.

- Streaming. SSE tee for both providers, OpenAI usage injection, incomplete-stream handling.

- Done when: streamed bytes equal the mock's, usage matches the non-streaming equivalent, and a cancelled stream records an incomplete event.

- Dashboard API and UI. Login, all /api endpoints, Overview, Agents (rename, merge), Runs, Connect app.

- Done when: the Playwright smoke test passes and the screens match the design canvas's layout.

- Budgets and alerts. Budgets CRUD, alert evaluation, webhook, dashboard banner, optional enforcement.

- Done when: tests cross 80% and 100% and see exactly one alert each; enforced budgets return 429.

- Prices and export. Seed file from official pricing pages, price editor, recompute, CSV export.

- Done when: an unpriced model shows in Settings, adding its price and recomputing fills the old events.

- Release 0.1.0. README, docs, screenshots, release workflow pushing the image to GHCR on a v* tag.

- Done when: on a clean machine, the README quick start works end to end in under five minutes.

### README and publishing checklist

The README is the product page, so it leads with the one-line pitch, a screenshot and a three-step quick start.

- ☐ One-line pitch and a dashboard screenshot or GIF at the top.

- ☐ Quick start: copy .env.example, docker compose up, set your SDK base URL.

- ☐ Before/after snippet showing the only config change, for OpenAI and Anthropic, in Java, Python and Node.

- ☐ Agent naming: URL path, header, automatic fingerprint; runs and tags.

- ☐ Configuration table (all environment variables).

- ☐ Privacy statement: what is stored, what never is.

- ☐ Honest comparison with Helicone, Langfuse, AgentOps and LiteLLM, including when to pick them instead.

- ☐ Known limitations: price tiers not modelled, enforcement may overshoot slightly, extra final chunk when stream usage is injected.

- ☐ LICENSE (Apache-2.0), CONTRIBUTING.md, SECURITY.md, issue and PR templates.

- ☐ Release workflow: tag v0.1.0 → image on GHCR with latest and version tags.

- ☐ Confirm the project name is free on GitHub and npm/Docker Hub before announcing.

- ☐ Launch posts: Show HN, r/selfhosted, r/LLMDevs, r/java, dev.to write-up, LinkedIn.
