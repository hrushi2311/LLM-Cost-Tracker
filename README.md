# LLM Cost Tracker

A lightweight, self-hosted LLM cost tracker that discovers your agents automatically. One container plus Postgres, any language, no SDK.

> **Status: milestone 2 of 9.** The proxy forwards OpenAI and Anthropic traffic, and records tokens and cost for non-streaming OpenAI Chat Completions. Anthropic Messages, the Responses API, agents, streaming and the dashboard come in later milestones. See [SPEC.md](SPEC.md) for the full design and build order.

## Quick start

Requires Docker with Compose v2.

```sh
cp .env.example .env        # then set DB_PASSWORD and TRACKER_ADMIN_PASSWORD (12+ chars)
docker compose up -d --build
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
```

### Create a project key

Until the dashboard exists (milestone 6), create projects from the command line. The key is printed once; only its hash is stored.

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

| Provider | Proxy base URL | Forwards to |
|---|---|---|
| `openai` | `http://host:8080/proxy/openai` | `TRACKER_OPENAI_BASE_URL` (default `https://api.openai.com/v1`) |
| `anthropic` | `http://host:8080/proxy/anthropic` | `TRACKER_ANTHROPIC_BASE_URL` (default `https://api.anthropic.com`) |

An optional `/agent/{name}` segment (`/proxy/openai/agent/planner/...`) is stripped before forwarding; agent attribution arrives in milestone 4.

## What gets recorded

| Endpoint | Recorded from |
|---|---|
| OpenAI Chat Completions (`POST …/chat/completions`), non-streaming | milestone 2 |
| OpenAI Responses, Anthropic Messages | milestone 3 |
| Streaming responses | milestone 5 (forwarded unchanged until then) |

All other paths are forwarded unchanged and not recorded. For each recorded call the tracker stores the model, uncached input, output, cache-read and reasoning tokens, cost, status code and latency, and nothing else.

Cost uses the `model_price` table: the row in effect on the call's date, matching the exact model name first, then the longest stored prefix (so `gpt-4o-2024-08-06` is priced as `gpt-4o`). A model with no price is stored with a null cost and can be priced later. The price table ships empty until milestone 8, so add rows yourself for now.

Recording happens after the response has been sent and never delays or fails a call. If the in-memory queue is full or Postgres is down, events are dropped and counted in the `tracker.events.dropped` metric.

## Privacy

- **Never stored or logged:** prompts, completions, system prompts, and request or response bodies.
- **Never stored or logged:** provider API keys (`Authorization`, `x-api-key`) and project keys. Log output passes through a redacting filter as a safety net.
- **Never forwarded:** `X-Tracker-*` headers are removed before forwarding.
- **Stored:** counts, costs, timings, model names, and the SHA-256 hash plus last 4 characters of each project key.

## Configuration

Everything is configured through environment variables; see [.env.example](.env.example) for the full list with a comment on each.

| Variable | Default | Purpose |
|---|---|---|
| `DB_PASSWORD` | required | Postgres password (compose only) |
| `TRACKER_ADMIN_PASSWORD` | required | Dashboard login; the app refuses to start if empty or shorter than 12 characters |
| `TRACKER_REQUIRE_PROJECT_KEY` | `true` | Reject proxy calls without a valid `X-Tracker-Key` (401). Never expose the proxy publicly with this off |
| `TRACKER_OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI upstream |
| `TRACKER_ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Anthropic upstream |
| `TRACKER_CONNECT_TIMEOUT` | `10s` | Upstream connect timeout |
| `TRACKER_UPSTREAM_TIMEOUT` | `10m` | Upstream response timeout |
| `TRACKER_MAX_REQUEST_BODY` | `20MB` | Largest accepted request body (413 above it) |
| `TRACKER_QUEUE_CAPACITY` | `10000` | Usage event queue size |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/llmtracker` | Point at your own Postgres 16 to run only the `app` service |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `tracker` / required | Database credentials |
| `SERVER_PORT` | `8080` | HTTP port |

Run the tracker behind a TLS-terminating reverse proxy for anything beyond localhost.

## Health

`/actuator/health` (overall), `/actuator/health/liveness` and `/actuator/health/readiness` (includes the database) are public. No other actuator endpoints are exposed.

## Development

Requires JDK 21 or newer and Docker (tests use Testcontainers for Postgres and MockWebServer in place of the providers; no real API keys are used).

```sh
./gradlew :backend:test       # unit + integration tests
./gradlew :backend:bootJar    # backend/build/libs/llm-cost-tracker.jar
```

Design choices made during the build are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).

## Licence

Apache-2.0 (LICENSE file to be added with the 0.1.0 release).
