# LLM Cost Tracker

A lightweight, self-hosted LLM cost tracker that discovers your agents automatically. One container plus Postgres, any language, no SDK.

> **Status: milestone 6 of 9 (API part).** The proxy forwards OpenAI, Anthropic and custom OpenAI-compatible traffic, records tokens and cost for OpenAI Chat Completions, OpenAI Responses and Anthropic Messages (streaming and non-streaming), and attributes every call to an agent and an optional run. The dashboard's REST API is in place; the dashboard UI, budget alerts and seeded prices come next. See [SPEC.md](SPEC.md) for the full design and build order.

## Quick start

Requires Docker with Compose v2.

```sh
cp .env.example .env        # then set DB_PASSWORD and TRACKER_ADMIN_PASSWORD (12+ chars)
docker compose up -d --build
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
```

### Create a project key

Create projects with `POST /api/projects` (see [Dashboard API](#dashboard-api)) or from the command line. The key is printed once; only its hash is stored.

```sh
docker compose run --rm app --create-project=my-app
# Created project "my-app" (id 6f1c...).
# Project key, shown only once. Send it as the X-Tracker-Key header:
# trk_4Xq...
```

Outside Docker: `java -jar llm-cost-tracker.jar --create-project=my-app` with the same database settings.

### Point your app at the proxy

Change the base URL and add the project key header. Your provider API key stays in your app and is passed through untouched.

```python
# Python (openai)
client = OpenAI(base_url="http://localhost:8080/proxy/openai",
                default_headers={"X-Tracker-Key": "trk_..."})
```

```java
// Java (openai-java)
OpenAIClient client = OpenAIOkHttpClient.builder()
        .fromEnv()
        .baseUrl("http://localhost:8080/proxy/openai")
        .putHeader("X-Tracker-Key", "trk_...")
        .build();
```

```js
// Node (openai)
const client = new OpenAI({ baseURL: "http://localhost:8080/proxy/openai",
                            defaultHeaders: { "X-Tracker-Key": "trk_..." } });
```

Anthropic works the same way; `anthropic-version`, `anthropic-beta` and every other header pass through unchanged.

```python
# Python (anthropic)
client = Anthropic(base_url="http://localhost:8080/proxy/anthropic",
                   default_headers={"X-Tracker-Key": "trk_..."})
```

```java
// Java (anthropic-java)
AnthropicClient client = AnthropicOkHttpClient.builder()
        .fromEnv()
        .baseUrl("http://localhost:8080/proxy/anthropic")
        .putHeader("X-Tracker-Key", "trk_...")
        .build();
```

```js
// Node (@anthropic-ai/sdk)
const client = new Anthropic({ baseURL: "http://localhost:8080/proxy/anthropic",
                               defaultHeaders: { "X-Tracker-Key": "trk_..." } });
```

| Provider | Proxy base URL | Forwards to |
|---|---|---|
| `openai` | `http://host:8080/proxy/openai` | `TRACKER_OPENAI_BASE_URL` (default `https://api.openai.com/v1`) |
| `anthropic` | `http://host:8080/proxy/anthropic` | `TRACKER_ANTHROPIC_BASE_URL` (default `https://api.anthropic.com`) |
| any name you configure | `http://host:8080/proxy/{name}` | the URL given in `TRACKER_CUSTOM_PROVIDERS` |

### Custom OpenAI-compatible providers

Any service that speaks the OpenAI API (Groq, Together, Ollama, vLLM, LM Studio and so on) can be added by name:

```sh
TRACKER_CUSTOM_PROVIDERS=groq=https://api.groq.com/openai/v1,local=http://ollama:11434/v1
```

Point the OpenAI SDK at `http://host:8080/proxy/groq`. Calls are parsed like OpenAI's and stored with `provider = groq`, so they are priced from `groq` rows in the price table. The proxy only ever calls the configured URL. Names must be lowercase (`a-z`, `0-9`, `-`), and a malformed entry stops the app at startup.

### Project key in the URL (when you can only change the base URL)

Some tools let you set a base URL but not extra headers. For those, put the project key in the path instead:

```
OPENAI_BASE_URL=http://localhost:8080/proxy/k/trk_.../openai
```

The `X-Tracker-Key` header is still the recommended option: URLs end up in more places (shell history, client-side logs, proxies) than headers do. The tracker masks the key in every log line it writes, including the access log, and never forwards it upstream. If both are given, the header wins.

## Agents, runs and tags

Every recorded call belongs to exactly one agent, found in this order (first match wins):

1. **Header:** `X-Tracker-Agent: researcher`.
2. **URL path:** `http://host:8080/proxy/openai/agent/researcher` as the base URL.
3. **Fingerprint:** a SHA-256 hash of the system prompt (Chat Completions `system`/`developer` messages, Responses `instructions`, Anthropic `system`). The agent is named `agent-a3f9` from the first hex characters, and calls with the same system prompt land on the same agent whatever the user message. Only the hash is stored, never the prompt.
4. Otherwise the agent named `unattributed`.

Agents are created on first sight; there is nothing to register. Names are normalised to lowercase slugs (`Customer Support Bot` becomes `customer-support-bot`). Set `TRACKER_FINGERPRINT_MASK=true` if your system prompts are templates: numbers, UUIDs, ISO dates and email addresses are then replaced before hashing, so `Customer 1042 …` and `Customer 77 …` stay one agent.

Agents can be renamed (display name only; the slug keeps matching) and merged. A merge moves the old agent's calls to the new one and records its name and fingerprint as aliases, so later calls follow automatically. Both are available through the [dashboard API](#dashboard-api).

**Runs:** send `X-Tracker-Run-Id: <id>` (up to 128 printable characters) on every call of one task to group them, across agents.

**Tags:** `X-Tracker-Tag-<key>: <value>` adds free-form tags (up to 10 per call; key up to 32, value up to 128 characters). Invalid or extra tags are ignored.

All `X-Tracker-*` headers are removed before the request is forwarded.

## What gets recorded

| Endpoint | Recorded from |
|---|---|
| OpenAI Chat Completions (`POST …/chat/completions`), streaming and non-streaming | yes |
| OpenAI Responses (`POST …/responses`), streaming and non-streaming | yes |
| Anthropic Messages (`POST /v1/messages`), streaming and non-streaming | yes |
| The same paths on custom OpenAI-compatible providers | yes |

All other paths are forwarded unchanged and not recorded. For each recorded call the tracker stores the agent, run ID, tags, model, uncached input, output, cache-read, cache-write and reasoning tokens, cost, status code and latency, and nothing else.

Token counts are normalised so the same columns mean the same thing for every provider. OpenAI reports input including cached tokens, so the cached part is subtracted. Anthropic's `input_tokens` already excludes cache reads and writes, so it is stored as reported. Reasoning and thinking tokens are already counted in output; they are stored for display and never charged twice.

Cost uses the `model_price` table: the row in effect on the call's date, matching the exact model name first, then the longest stored prefix (so `gpt-4o-2024-08-06` is priced as `gpt-4o`). A model with no price is stored with a null cost and can be priced later. The price table ships empty until milestone 8, so add rows yourself for now.

**Known limitations:** streamed Chat Completions add one extra final chunk unless you set `stream_options` yourself (see Streaming). Anthropic charges more to write a 1-hour cache entry (2× input) than a 5-minute one (1.25× input). v0.1 uses a single cache-write price per model, so 1-hour cache writes are under-costed. Batch, long-context and regional price tiers are not modelled either.

Recording happens after the response has been sent and never delays or fails a call. If the in-memory queue is full or Postgres is down, events are dropped and counted in the `tracker.events.dropped` metric.

### Streaming

Streamed responses (server-sent events) are passed to your app chunk by chunk as they arrive, unchanged; nothing is buffered. The tracker reads usage from a copy of each chunk while keeping only a small fixed window (128 KB) of the current event, however long the stream is:

- **OpenAI Chat Completions:** usage is only sent when the request has `stream_options.include_usage: true`. If your request does not set `stream_options` at all, the tracker adds `"stream_options":{"include_usage":true}` and changes nothing else in the request. **Your app then receives one extra final chunk before `data: [DONE]`, with an empty `choices` array and the `usage` for the whole request.** The official SDKs handle this chunk; code that reads `choices[0]` from every chunk without checking should skip chunks with empty `choices`. Turn this off with `TRACKER_OPENAI_INJECT_STREAM_USAGE=false`. If you set `stream_options` yourself, even to `include_usage: false`, the request is left untouched. Without usage the call is recorded as incomplete, with zero tokens.
- **OpenAI Responses:** usage comes from the `response.completed` event.
- **Anthropic Messages:** input and cache tokens come from `message_start`; output tokens come from the last `message_delta`, whose counts are cumulative.

If your app disconnects mid-stream, the tracker closes the upstream request too, so the provider stops generating (and billing), and records the call as incomplete with whatever usage it had seen. If the provider sends an error event or drops the connection mid-stream, your app receives exactly that, and the call is recorded as incomplete.

## Dashboard API

The dashboard reads everything through `/api/**`. Sign in first; every other call needs the session.

```sh
# Sign in: sets an HttpOnly, SameSite=Strict session cookie and returns a CSRF token.
curl -c jar -H 'Content-Type: application/json' -d '{"password":"<TRACKER_ADMIN_PASSWORD>"}'      http://localhost:8080/api/auth/login
# {"csrfToken":"Qm9…"}

curl -b jar http://localhost:8080/api/projects
curl -b jar -H 'X-CSRF-Token: Qm9…' -H 'Content-Type: application/json' -d '{"name":"my-app"}'      http://localhost:8080/api/projects        # returns the full key once
```

| Method | Path | Returns or does |
|---|---|---|
| POST | `/api/auth/login` | `{"password"}` → session cookie + `{"csrfToken"}`. 5 attempts per minute per IP |
| POST | `/api/auth/logout` | Ends the session |
| GET | `/api/auth/session` | `{"csrfToken"}` for the current session (after a page reload) |
| GET, POST | `/api/projects` | Projects with masked keys; create one (`{"name"}`), full key returned once |
| POST | `/api/projects/{id}/rotate-key` | New key, returned once; the old key stops working at once |
| GET | `/api/projects/{id}/summary` | Cost (and change vs the previous period), tokens, requests, runs, average cost per run, errors and error rate, incomplete calls and their rate, unpriced calls, project budgets with projection |
| GET | `/api/projects/{id}/timeseries?groupBy=agent\|model&interval=hour\|day` | Cost and requests per bucket per agent or model; empty buckets are zeros |
| GET | `/api/projects/{id}/models` | Per model: provider, requests, tokens, cost, share, unpriced flag |
| GET | `/api/projects/{id}/agents` | Per agent: source, models with cost, requests, tokens, cost, budgets, first and last seen |
| PATCH | `/api/agents/{id}` | `{"displayName"}` (null clears it) |
| POST | `/api/agents/{id}/merge` | `{"intoAgentId"}` |
| GET | `/api/projects/{id}/runs?cursor=` | Runs newest first, 50 per page, with `nextCursor` |
| GET | `/api/projects/{id}/runs/{runId}` | The run's calls in order, with offsets for a waterfall |
| GET, POST, PUT, DELETE | `/api/projects/{id}/budgets[/{budgetId}]` | Budgets: `{"agentId"?, "period":"day"\|"month", "limitUsd", "alertPct"?, "enforce"?}` |
| GET | `/api/projects/{id}/alerts` | The 100 most recent budget alerts |
| GET, POST | `/api/prices` | Price rows plus models seen without a price; add a row (`source = user`) |
| POST | `/api/prices/recompute[?all=true]` | Re-price unpriced events (or, with `all=true`, every event in the range), 5,000 at a time |
| GET | `/api/projects/{id}/events.csv` | Streams the range's events as CSV |
| GET | `/api/status[?projectId=]` | Version, last event time, events dropped, queue depth |

- **Ranges:** `from` and `to` take an ISO-8601 instant (`2026-11-01T00:00:00Z`) or a date (midnight in `TRACKER_TIMEZONE`). Default: the last 14 days. Day buckets and budget periods follow `TRACKER_TIMEZONE`.
- **Money** is always a decimal string (`"412.86000000"`), never a JSON number. Shares and rates are fractions as strings (`"0.4170"`).
- **Errors** have one shape: `{"error":{"code":"validation_error","message":"limitUsd must be greater than 0"}}`. Unknown fields in a request body are rejected.
- **CSRF:** `POST`, `PUT`, `PATCH` and `DELETE` need the token from login in an `X-CSRF-Token` header.
- **Sessions** last 12 idle hours (7 days at most) and are kept in memory, so a restart signs you out. The cookie is marked `Secure` when the request arrives over HTTPS; behind a TLS-terminating reverse proxy set `SERVER_FORWARD_HEADERS_STRATEGY=native` so the tracker sees the original scheme and client IP.
- **Budgets** can be created and edited now; alerts and enforcement arrive in milestone 7.

### Demo data

For trying the dashboard, or for screenshots, create a demo project with 30 days of made-up traffic: four agents (two by URL path, one by header, one by fingerprint that shows up a week in), four models, multi-call runs, a few upstream errors and incomplete streams, and monthly budgets.

```sh
docker compose run --rm app --seed-demo              # project "demo", about 25,000 events
docker compose run --rm app --seed-demo=screenshots --seed-demo-events=100000
```

It only ever runs when you pass the flag. Costs use your price table where it has the model, otherwise round placeholder rates that are not real provider prices.

## Privacy

- **Never stored or logged:** prompts, completions, system prompts, and request or response bodies.
- **Never stored or logged:** provider API keys (`Authorization`, `x-api-key`) and project keys. Log output passes through a redacting filter as a safety net.
- **Never forwarded:** `X-Tracker-*` headers are removed before forwarding.
- **Never stored or logged:** system prompts. Only their SHA-256 fingerprint is kept.
- **Never logged:** the dashboard password, session cookie and CSRF token.
- **Never logged:** a project key given in the URL (`/proxy/k/{key}/…`). It is masked in application, error and access logs.
- **Stored:** counts, costs, timings, model names, agent names, run IDs and tags you send, system-prompt fingerprints, and the SHA-256 hash plus last 4 characters of each project key.

## Configuration

Everything is configured through environment variables; see [.env.example](.env.example) for the full list with a comment on each.

| Variable | Default | Purpose |
|---|---|---|
| `DB_PASSWORD` | required | Postgres password (compose only) |
| `TRACKER_ADMIN_PASSWORD` | required | Dashboard login; the app refuses to start if empty or shorter than 12 characters |
| `TRACKER_REQUIRE_PROJECT_KEY` | `true` | Reject proxy calls without a valid `X-Tracker-Key` (401). Never expose the proxy publicly with this off |
| `TRACKER_OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI upstream |
| `TRACKER_ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Anthropic upstream |
| `TRACKER_CUSTOM_PROVIDERS` | empty | `name=url` pairs, comma separated, for OpenAI-compatible providers |
| `TRACKER_CONNECT_TIMEOUT` | `10s` | Upstream connect timeout |
| `TRACKER_UPSTREAM_TIMEOUT` | `10m` | Upstream response timeout |
| `TRACKER_MAX_REQUEST_BODY` | `20MB` | Largest accepted request body (413 above it) |
| `TRACKER_QUEUE_CAPACITY` | `10000` | Usage event queue size |
| `TRACKER_OPENAI_INJECT_STREAM_USAGE` | `true` | Add `stream_options.include_usage` to streamed Chat Completions that do not set `stream_options` (adds one final chunk) |
| `TRACKER_FINGERPRINT_ENABLED` | `true` | Discover agents from a system-prompt fingerprint |
| `TRACKER_FINGERPRINT_MASK` | `false` | Mask numbers, UUIDs, ISO dates and emails before fingerprinting |
| `TRACKER_ACCESS_LOG` | `false` | HTTP access log (project keys in paths are masked) |
| `TRACKER_TIMEZONE` | `UTC` | Zone for day buckets on the dashboard and for budget periods (use current names such as `Asia/Kolkata`) |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/llmtracker` | Point at your own Postgres 16 to run only the `app` service |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `tracker` / required | Database credentials |
| `SERVER_PORT` | `8080` | HTTP port |

Run the tracker behind a TLS-terminating reverse proxy for anything beyond localhost.

## Health

`/actuator/health` (overall), `/actuator/health/liveness` and `/actuator/health/readiness` (includes the database) are public. No other actuator endpoints are exposed.

## Development

Requires JDK 21 or newer and Docker (tests use Testcontainers for Postgres and MockWebServer in place of the providers; no real API keys are used).

```sh
./gradlew :backend:test       # unit, integration and API tests
./gradlew :backend:perfTest   # manual: seeds 1M events and times the dashboard API (also the manual "perf" workflow)
./gradlew :backend:bootJar    # backend/build/libs/llm-cost-tracker.jar
```

Design choices made during the build are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).

## Licence

Apache-2.0 (LICENSE file to be added with the 0.1.0 release).
